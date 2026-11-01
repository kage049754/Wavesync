package com.wavesync.app

import android.media.*
import android.graphics.*
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.concurrent.Executors

@Composable
fun VideoEditorDialog(
    video: File, modelReady: Boolean, busy: Boolean, onDismiss: () -> Unit,
    onTranscribe: (File) -> Unit, onTrim: (File, Long, Long) -> Unit,
    onThumbnail: (File, String) -> Unit, onShare: (File) -> Unit
) {
    var duration by remember(video.absolutePath) { mutableLongStateOf(0L) }
    var position by remember(video.absolutePath) { mutableLongStateOf(0L) }
    var trimStart by remember(video.absolutePath) { mutableFloatStateOf(0f) }
    var trimEnd by remember(video.absolutePath) { mutableFloatStateOf(0f) }
    var title by remember(video.absolutePath) { mutableStateOf(video.nameWithoutExtension.take(42)) }
    var player by remember { mutableStateOf<VideoView?>(null) }
    var playing by remember { mutableStateOf(false) }
    val maxDuration = duration.coerceAtLeast(1L).toFloat()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f), shape = MaterialTheme.shapes.extraLarge, color = Color(0xFF101827)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("ClipForge editor", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(video.name, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9AA8BC))
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                AndroidView(factory = { context ->
                    VideoView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(-1, -1)
                        setVideoPath(video.absolutePath)
                        setOnPreparedListener { media -> duration = media.duration.toLong().coerceAtLeast(0L); trimEnd = duration.toFloat() }
                        setOnCompletionListener { playing = false }
                        player = this
                    }
                }, modifier = Modifier.fillMaxWidth().height(215.dp), update = { player = it })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val view = player ?: return@Button
                        if (view.isPlaying) { view.pause(); playing = false } else { view.start(); playing = true }
                    }) { Text(if (playing) "Pause" else "Play") }
                    OutlinedButton(onClick = { player?.seekTo(0); position = 0L }) { Text("Restart") }
                }
                LaunchedEffect(player) {
                    while (true) {
                        val view = player
                        if (view != null && view.isPlaying) position = view.currentPosition.toLong()
                        kotlinx.coroutines.delay(250)
                    }
                }
                Text("Position: ${clock(position)} / ${clock(duration)}", color = Color(0xFF9AA8BC))
                Slider(value = position.coerceIn(0L, duration.coerceAtLeast(1L)).toFloat(), onValueChange = { position = it.toLong(); player?.seekTo(position.toInt()) }, valueRange = 0f..maxDuration)
                HorizontalDivider(color = Color(0xFF34445A))
                Text("Trim / cut", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Start: ${clock(trimStart.toLong())}", color = Color(0xFF9AA8BC))
                Slider(value = trimStart.coerceIn(0f, maxDuration), onValueChange = { trimStart = it.coerceAtMost((trimEnd - 500f).coerceAtLeast(0f)); player?.seekTo(trimStart.toInt()) }, valueRange = 0f..maxDuration)
                Text("End: ${clock(trimEnd.toLong())}", color = Color(0xFF9AA8BC))
                Slider(value = trimEnd.coerceIn(0f, maxDuration), onValueChange = { trimEnd = it.coerceAtLeast(trimStart + 500f).coerceAtMost(maxDuration); player?.seekTo(trimEnd.toInt()) }, valueRange = 0f..maxDuration)
                Text("Fast export copies the selected media without re-encoding. The start can move back to the nearest keyframe.", color = Color(0xFF9AA8BC), style = MaterialTheme.typography.bodySmall)
                Button(enabled = duration > 0L && trimEnd - trimStart >= 500f && !busy, onClick = { player?.pause(); playing = false; onTrim(video, trimStart.toLong(), trimEnd.toLong()) }, modifier = Modifier.fillMaxWidth()) { Text("Export trimmed MP4") }
                HorizontalDivider(color = Color(0xFF34445A))
                Text("Automatic English subtitles", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(if (modelReady) "Generate timestamped SRT subtitles locally with the installed English model." else "Download the English model once from the main screen first.", color = Color(0xFF9AA8BC), style = MaterialTheme.typography.bodySmall)
                Button(enabled = modelReady && !busy, onClick = { player?.pause(); playing = false; onTranscribe(video) }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Processing..." else "Generate subtitles") }
                HorizontalDivider(color = Color(0xFF34445A))
                Text("Thumbnail maker", color = Color.White, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, modifier = Modifier.fillMaxWidth(), label = { Text("Thumbnail title") }, singleLine = true)
                Button(enabled = duration > 0L && !busy, onClick = { player?.pause(); playing = false; onThumbnail(video, title) }, modifier = Modifier.fillMaxWidth()) { Text("Create 1280 × 720 thumbnail") }
                Button(onClick = { onShare(video) }, modifier = Modifier.fillMaxWidth()) { Text("Upload / share video…") }
            }
        }
    }
}

private fun clock(ms: Long): String {
    val sec = ms.coerceAtLeast(0L) / 1000L
    return "%02d:%02d".format(sec / 60L, sec % 60L)
}

object VideoEditor {
    private val executor = Executors.newSingleThreadExecutor()

    fun trim(source: File, startMs: Long, endMs: Long, complete: (Result<File>) -> Unit) {
        executor.execute {
            val result = runCatching {
                require(source.isFile && endMs > startMs) { "Invalid video or trim range" }
                val output = File(source.parentFile, source.nameWithoutExtension + "_trim_${System.currentTimeMillis()}.mp4")
                val extractor = MediaExtractor()
                var muxer: MediaMuxer? = null
                try {
                    extractor.setDataSource(source.absolutePath)
                    val map = HashMap<Int, Int>()
                    val formats = ArrayList<Pair<Int, MediaFormat>>()
                    var videoTrack = -1
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                            formats.add(i to format)
                            if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                        }
                    }
                    require(videoTrack >= 0) { "No video track found" }
                    muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    formats.forEach { (input, format) -> map[input] = muxer!!.addTrack(format); extractor.selectTrack(input) }
                    extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val actualStart = extractor.sampleTime.coerceAtLeast(0L)
                    muxer!!.start()
                    val maxSize = formats.mapNotNull { (index, format) -> if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else null }.maxOrNull()?.coerceIn(256 * 1024, 16 * 1024 * 1024) ?: 4 * 1024 * 1024
                    val buffer = java.nio.ByteBuffer.allocateDirect(maxSize)
                    val info = MediaCodec.BufferInfo()
                    var samples = 0
                    while (extractor.sampleTrackIndex >= 0) {
                        val track = extractor.sampleTrackIndex
                        val time = extractor.sampleTime
                        if (time > endMs * 1000L) break
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        if (map.containsKey(track) && time >= actualStart) {
                            info.set(0, size, time - actualStart, extractor.sampleFlags)
                            muxer!!.writeSampleData(map[track]!!, buffer, info)
                            samples++
                        }
                        extractor.advance()
                    }
                    require(samples > 0) { "No samples found in selected range" }
                    muxer!!.stop()
                    output
                } finally {
                    runCatching { muxer?.release() }
                    extractor.release()
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { complete(result) }
        }
    }

    fun createThumbnail(video: File, title: String, complete: (Result<File>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.absolutePath)
                    val frame = retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: error("Could not extract a video frame")
                    val bitmap = Bitmap.createScaledBitmap(frame, 1280, 720, true)
                    if (bitmap !== frame) frame.recycle()
                    val canvas = Canvas(bitmap)
                    val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(165, 4, 10, 20) }
                    canvas.drawRoundRect(RectF(30f, 470f, 1250f, 690f), 24f, 24f, shade)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = AndroidColor.WHITE; textSize = 66f
                        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                        setShadowLayer(8f, 2f, 3f, AndroidColor.BLACK)
                    }
                    val words = title.ifBlank { video.nameWithoutExtension }.take(80).split(" ")
                    val lines = ArrayList<String>()
                    var line = ""
                    words.forEach { word ->
                        val candidate = if (line.isBlank()) word else "$line $word"
                        if (paint.measureText(candidate) > 1120f && line.isNotBlank()) { lines.add(line); line = word } else line = candidate
                    }
                    if (line.isNotBlank()) lines.add(line)
                    lines.take(2).forEachIndexed { index, text -> canvas.drawText(text, 65f, 555f + index * 82f, paint) }
                    val file = File(video.parentFile, video.nameWithoutExtension + "_thumbnail.jpg")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    bitmap.recycle()
                    file
                } finally { retriever.release() }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { complete(result) }
        }
    }
}
