package com.wavesync.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioAttributes
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordingService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var outputFile: File? = null
    private var stopping = false
    private var playbackRecord: AudioRecord? = null
    private var playbackThread: Thread? = null
    private var playbackFile: File? = null
    private var recordingStartedAtNs: Long = 0L
    private var playbackAudioOffsetUs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var playbackRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRecording(); return START_NOT_STICKY }
            ACTION_START -> {
                startForegroundCompat()
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent)
                if (code == 0 || data == null) { stopSelf(); return START_NOT_STICKY }
                try { beginRecording(code, data, intent.getIntExtra(EXTRA_FPS,60), intent.getStringExtra(EXTRA_QUALITY) ?: "1080p", intent.getBooleanExtra(EXTRA_MIC,false), intent.getBooleanExtra(EXTRA_PLAYBACK,false)) }
                catch (e: Exception) { getSharedPreferences("clipforge", Context.MODE_PRIVATE).edit().putString("last_error", e.message ?: "Recording could not start").putBoolean("recording_active",false).apply(); stopRecording() }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val channel = "clipforge_recording"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel,"ClipForge recording",NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this,1,Intent(this,ScreenRecordingService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this,channel)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("ClipForge is recording")
            .setContentText("Tap Stop to finish and save your video")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause,"Stop",stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID,notification)
    }

    private fun beginRecording(code:Int,data:Intent,fps:Int,quality:String,microphone:Boolean,playbackAudio:Boolean) {
        stopping = false
        playbackFile = null
        playbackAudioOffsetUs = 0L
        getSharedPreferences("clipforge", Context.MODE_PRIVATE).edit()
            .putString("last_error", "")
            .putString("last_mux_status", "")
            .apply()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(code,data)
        projection?.registerCallback(object:MediaProjection.Callback() {
            override fun onStop() { stopRecording() }
        },null)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val maxDimension = if (quality == "720p") 720 else 1080
        val scale = maxDimension.toFloat() / maxOf(metrics.widthPixels,metrics.heightPixels).toFloat()
        val requestedWidth = ((metrics.widthPixels * scale).toInt() / 2) * 2
        val requestedHeight = ((metrics.heightPixels * scale).toInt() / 2) * 2
        val supported = chooseSupportedCapture(requestedWidth, requestedHeight, fps)
        val width = supported.first
        val height = supported.second
        val actualFps = supported.third
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES),"ClipForge").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())
        outputFile = File(dir,"ClipForge_$stamp.mp4")
        val media = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = media
        media.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        if (microphone) media.setAudioSource(MediaRecorder.AudioSource.MIC)
        media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        media.setOutputFile(outputFile!!.absolutePath)
        media.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        media.setVideoSize(width.coerceAtLeast(2),height.coerceAtLeast(2))
        media.setVideoFrameRate(actualFps)
        media.setVideoEncodingBitRate(if (quality == "720p") 6_000_000 else 12_000_000)
        if (microphone) { media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); media.setAudioEncodingBitRate(128000); media.setAudioSamplingRate(44100) }
        media.prepare()
        val surface: Surface = media.surface
        display = projection!!.createVirtualDisplay("ClipForgeCapture",width,height,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,null)
        media.start()
        recordingStartedAtNs = SystemClock.elapsedRealtimeNanos()
        if (playbackAudio) {
            if (Build.VERSION.SDK_INT >= 29) startPlaybackCapture(projection ?: error("Screen projection was not initialized"), outputFile ?: error("Recording file was not created"))
            else getSharedPreferences("clipforge", Context.MODE_PRIVATE).edit().putString("last_error", "App audio capture requires Android 10 or newer").apply()
        }
        getSharedPreferences("clipforge",Context.MODE_PRIVATE).edit().putString("last_recording",outputFile!!.absolutePath).putString("last_error","").putBoolean("recording_active",true).apply()
    }


    @androidx.annotation.RequiresApi(29)
    private fun startPlaybackCapture(mediaProjection: MediaProjection, videoFile: File) {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minimum = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Playback audio capture is not supported on this device" }
        val audio = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum * 2, 16384))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize app audio capture" }
        playbackFile = File(videoFile.parentFile, videoFile.nameWithoutExtension + "_audio.wav")
        playbackRecord = audio
        playbackAudioOffsetUs = ((SystemClock.elapsedRealtimeNanos() - recordingStartedAtNs) / 1000L).coerceAtLeast(0L)
        File(videoFile.parentFile, videoFile.nameWithoutExtension + "_audio.offset").writeText(playbackAudioOffsetUs.toString())
        playbackRunning = true
        audio.startRecording()
        playbackThread = Thread {
            val target = playbackFile ?: return@Thread
            var dataBytes = 0L
            try {
                RandomAccessFile(target, "rw").use { wav ->
                    wav.setLength(0)
                    wav.write(ByteArray(44))
                    val buffer = ByteArray(16384)
                    while (playbackRunning) {
                        val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count > 0) {
                            val aligned = count - count % 4
                            if (aligned > 0) {
                                wav.write(buffer, 0, aligned)
                                dataBytes += aligned
                            }
                        } else if (count < 0) break
                    }
                    wav.seek(0)
                    wav.write("RIFF".toByteArray(Charsets.US_ASCII))
                    wav.writeIntLE((36L + dataBytes).coerceAtMost(0xffffffffL).toInt())
                    wav.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                    wav.writeIntLE(16)
                    wav.writeShortLE(1)
                    wav.writeShortLE(2)
                    wav.writeIntLE(44100)
                    wav.writeIntLE(44100 * 4)
                    wav.writeShortLE(4)
                    wav.writeShortLE(16)
                    wav.write("data".toByteArray(Charsets.US_ASCII))
                    wav.writeIntLE(dataBytes.coerceAtMost(0xffffffffL).toInt())
                }
                if (dataBytes == 0L) target.delete()
            } catch (e: Exception) {
                getSharedPreferences("clipforge", Context.MODE_PRIVATE).edit()
                    .putString("last_error", "App audio capture failed: ${e.message ?: "unsupported source"}").apply()
                target.delete()
            }
        }.apply { name = "ClipForgePlaybackAudio"; start() }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xff); write((value ushr 8) and 0xff); write((value ushr 16) and 0xff); write((value ushr 24) and 0xff)
    }
    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xff); write((value ushr 8) and 0xff)
    }

    private fun stopPlaybackCapture() {
        playbackRunning = false
        try { playbackRecord?.stop() } catch (_: Exception) {}
        try { playbackThread?.join(10000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        val writerStillRunning = playbackThread?.isAlive == true
        try { playbackRecord?.release() } catch (_: Exception) {}
        playbackRecord = null
        if (writerStillRunning) {
            playbackFile = null
            getSharedPreferences("clipforge", Context.MODE_PRIVATE).edit()
                .putString("last_error", "Playback audio writer did not stop cleanly; MP4 was left untouched").apply()
        }
        playbackThread = null
    }

    private fun chooseSupportedCapture(requestedWidth: Int, requestedHeight: Int, requestedFps: Int): Triple<Int, Int, Int> {
        val encoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        val candidates = listOf(
            requestedWidth to requestedHeight,
            ((requestedWidth * 0.85f).toInt() / 2 * 2) to ((requestedHeight * 0.85f).toInt() / 2 * 2),
            ((requestedWidth * 0.70f).toInt() / 2 * 2) to ((requestedHeight * 0.70f).toInt() / 2 * 2),
            ((requestedWidth * 0.50f).toInt() / 2 * 2) to ((requestedHeight * 0.50f).toInt() / 2 * 2)
        ).distinct()
        val rates = listOf(requestedFps.coerceIn(24, 60), 30, 24).distinct()
        for ((width, height) in candidates) {
            for (rate in rates) {
                val supported = encoders.any { info ->
                    runCatching {
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                            .videoCapabilities.areSizeAndRateSupported(width, height, rate.toDouble())
                    }.getOrDefault(false)
                }
                if (supported) return Triple(width, height, rate)
            }
        }
        throw IllegalStateException("No compatible H.264 encoder profile found for this display")
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true
        stopPlaybackCapture()
        try { recorder?.stop() } catch (_:Exception) {}
        try { display?.release() } catch (_:Exception) {}
        try { recorder?.reset(); recorder?.release() } catch (_:Exception) {}
        try { projection?.stop() } catch (_:Exception) {}
        recorder=null; display=null; projection=null
        val saved=outputFile?.takeIf { it.exists() && it.length()>0L }
        val wav = playbackFile?.takeIf { it.exists() && it.length()>44L }
        outputFile=null
        getSharedPreferences("clipforge",Context.MODE_PRIVATE).edit()
            .putString("last_recording",saved?.absolutePath ?: "")
            .putBoolean("recording_active",false)
            .putString("last_mux_status", if (saved != null && wav != null) "Finishing MP4 audio..." else "")
            .apply()
        if (saved != null && wav != null) {
            val notification = Notification.Builder(this, "clipforge_recording")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("ClipForge is finishing your video")
                .setContentText("Adding captured app audio to the MP4")
                .setOngoing(true)
                .build()
            try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification) } catch (_: Exception) {}
            Thread {
                val result = PlaybackAudioMuxer.mux(saved, wav, playbackAudioOffsetUs)
                val prefs = getSharedPreferences("clipforge", Context.MODE_PRIVATE).edit()
                if (result.isSuccess) {
                    prefs.putString("last_error", "")
                        .putString("last_mux_status", "Captured app audio was added to the MP4")
                } else {
                    prefs.putString("last_mux_status", "Audio mux failed; original MP4 and WAV were preserved")
                        .putString("last_error", "Audio mux failed: ${result.exceptionOrNull()?.message ?: "unknown error"}. Original MP4 was preserved.")
                }
                prefs.apply()
                mainHandler.post { finishStopping() }
            }.apply { name = "ClipForgeAudioMux"; start() }
        } else {
            finishStopping()
        }
    }

    private fun finishStopping() {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (_:Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopping = false
        stopSelf()
    }

    override fun onDestroy() { if (recorder != null || projection != null) stopRecording(); super.onDestroy() }

    companion object {
        const val ACTION_START="com.wavesync.app.START_CAPTURE"
        const val ACTION_STOP="com.wavesync.app.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE="result_code"
        const val EXTRA_RESULT_DATA="result_data"
        const val EXTRA_FPS="fps"
        const val EXTRA_QUALITY="quality"
        const val EXTRA_MIC="microphone"
        const val EXTRA_PLAYBACK="playback_audio"
        private const val NOTIFICATION_ID=9021
    }
}