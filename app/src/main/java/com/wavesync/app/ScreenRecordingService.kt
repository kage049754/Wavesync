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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordingService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var outputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRecording(); return START_NOT_STICKY }
            ACTION_START -> {
                startForegroundCompat()
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent)
                if (code == 0 || data == null) { stopSelf(); return START_NOT_STICKY }
                try { beginRecording(code, data, intent.getIntExtra(EXTRA_FPS,60), intent.getStringExtra(EXTRA_QUALITY) ?: "1080p", intent.getBooleanExtra(EXTRA_MIC,false)) }
                catch (e: Exception) { getSharedPreferences("clipforge", Context.MODE_PRIVATE).edit().putString("last_error", e.message ?: "Recording could not start").apply(); stopRecording() }
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

    private fun beginRecording(code:Int,data:Intent,fps:Int,quality:String,microphone:Boolean) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(code,data)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val maxDimension = if (quality == "720p") 720 else 1080
        val scale = maxDimension.toFloat() / maxOf(metrics.widthPixels,metrics.heightPixels).toFloat()
        val width = ((metrics.widthPixels * scale).toInt() / 2) * 2
        val height = ((metrics.heightPixels * scale).toInt() / 2) * 2
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
        media.setVideoFrameRate(fps.coerceIn(24,60))
        media.setVideoEncodingBitRate(if (quality == "720p") 6_000_000 else 12_000_000)
        if (microphone) { media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); media.setAudioEncodingBitRate(128000); media.setAudioSamplingRate(44100) }
        media.prepare()
        val surface: Surface = media.surface
        display = projection!!.createVirtualDisplay("ClipForgeCapture",width,height,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,null)
        media.start()
        projection?.registerCallback(object:MediaProjection.Callback() {
            override fun onStop() { stopRecording() }
        },null)
        getSharedPreferences("clipforge",Context.MODE_PRIVATE).edit().putString("last_recording",outputFile!!.absolutePath).putString("last_error","").apply()
    }

    private fun stopRecording() {
        try { recorder?.stop() } catch (_:Exception) {}
        try { display?.release() } catch (_:Exception) {}
        try { recorder?.reset(); recorder?.release() } catch (_:Exception) {}
        try { projection?.stop() } catch (_:Exception) {}
        recorder=null; display=null; projection=null
        val saved=outputFile?.takeIf { it.exists() && it.length()>0L }
        getSharedPreferences("clipforge",Context.MODE_PRIVATE).edit().putString("last_recording",saved?.absolutePath ?: "").apply()
        outputFile=null
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (_:Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        private const val NOTIFICATION_ID=9021
    }
}