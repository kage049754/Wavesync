package com.wavesync.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.ViewGroup
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.Executors

data class SubtitleStyle(
    val sizeSp: Float = 28f,
    val textColor: Int = android.graphics.Color.WHITE,
    val outlineColor: Int = android.graphics.Color.BLACK,
    val outlineWidth: Float = 3f,
    val font: String = "sans-serif",
    val entrance: String = "Fade",
    val exit: String = "Fade"
)

@Composable
fun VideoEditorDialog(
    video: File, modelReady: Boolean, busy: Boolean, onDismiss: () -> Unit,
    onTranscribe: (File) -> Unit,
    onTrim: (File, Long, Long, File?, File?, SubtitleStyle, String) -> Unit,
    onThumbnail: (File, String, Long) -> Unit, onShare: (File) -> Unit
) {
    val context = LocalContext.current
    var duration by remember(video.absolutePath) { mutableLongStateOf(0L) }
    var position by remember(video.absolutePath) { mutableLongStateOf(0L) }
    var trimStart by remember(video.absolutePath) { mutableFloatStateOf(0f) }
    var trimEnd by remember(video.absolutePath) { mutableFloatStateOf(0f) }
    var title by remember(video.absolutePath) { mutableStateOf(video.nameWithoutExtension.take(42)) }
    var overlayText by remember(video.absolutePath) { mutableStateOf("") }
    var player by remember { mutableStateOf<VideoView?>(null) }
    var playing by remember { mutableStateOf(false) }
    var music by remember { mutableStateOf<File?>(null) }
    var style by remember { mutableStateOf(SubtitleStyle()) }
    var subtitleFile by remember(video.absolutePath) {
        mutableStateOf(File(video.parentFile, video.nameWithoutExtension + ".srt").takeIf { it.exists() })
    }
    val maxDuration = duration.coerceAtLeast(1L).toFloat()
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val mime = context.contentResolver.getType(uri).orEmpty()
                require(mime.startsWith("audio/") || uri.toString().contains("audio")) { "Choose an audio file" }
                val target = File(video.parentFile, video.nameWithoutExtension + "_music_" + System.currentTimeMillis() + ".audio")
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: error("Could not read selected music")
                music = target
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.97f).fillMaxHeight(.94f), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("ClipForge timeline editor", style = MaterialTheme.typography.titleLarge)
                        Text(video.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                AndroidView(factory = { ctx ->
                    VideoView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(-1, -1)
                        setVideoPath(video.absolutePath)
                        setOnPreparedListener { media -> duration = media.duration.toLong().coerceAtLeast(0L); trimEnd = duration.toFloat() }
                        setOnCompletionListener { playing = false }
                        player = this
                    }
                }, modifier = Modifier.fillMaxWidth().height(205.dp), update = { player = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { val view = player ?: return@Button; if (view.isPlaying) { view.pause(); playing = false } else { view.start(); playing = true } }) { Text(if (playing) "Pause" else "Play") }
                    OutlinedButton(onClick = { player?.seekTo(0); position = 0L }) { Text("Restart") }
                }
                LaunchedEffect(player) {
                    while (true) {
                        player?.takeIf { it.isPlaying }?.let { position = it.currentPosition.toLong() }
                        kotlinx.coroutines.delay(200)
                    }
                }
                Text("Playhead  ${clock(position)} / ${clock(duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = position.coerceIn(0L, duration.coerceAtLeast(1L)).toFloat(), onValueChange = { position = it.toLong(); player?.seekTo(position.toInt()) }, valueRange = 0f..maxDuration)
                HorizontalDivider()
                Text("MULTI-TRACK TIMELINE", style = MaterialTheme.typography.titleMedium)
                TimelineLane("VIDEO", video.name, trimStart.toLong(), trimEnd.toLong(), duration)
                TimelineLane("SUBTITLES", subtitleFile?.name ?: "No SRT loaded", trimStart.toLong(), trimEnd.toLong(), duration)
                TimelineLane("OVERLAY", overlayText.ifBlank { "No text overlay" }, trimStart.toLong(), trimEnd.toLong(), duration)
                TimelineLane("MUSIC", music?.name ?: "No music track", 0L, duration, duration)
                Text("Clip range: ${clock(trimStart.toLong())} – ${clock(trimEnd.toLong())}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("In point")
                Slider(value = trimStart.coerceIn(0f, maxDuration), onValueChange = { trimStart = it.coerceAtMost((trimEnd - 250f).coerceAtLeast(0f)); player?.seekTo(trimStart.toInt()) }, valueRange = 0f..maxDuration)
                Text("Out point")
                Slider(value = trimEnd.coerceIn(0f, maxDuration), onValueChange = { trimEnd = it.coerceAtLeast(trimStart + 250f).coerceAtMost(maxDuration); player?.seekTo(trimEnd.toInt()) }, valueRange = 0f..maxDuration)
                HorizontalDivider()
                Text("SUBTITLE DESIGN", style = MaterialTheme.typography.titleMedium)
                Text("Font size: ${style.sizeSp.toInt()} px")
                Slider(value = style.sizeSp, onValueChange = { style = style.copy(sizeSp = it) }, valueRange = 16f..64f)
                Text("Text color")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("White" to android.graphics.Color.WHITE, "Yellow" to android.graphics.Color.YELLOW, "Cyan" to android.graphics.Color.CYAN, "Red" to android.graphics.Color.RED).forEach { (label, color) ->
                        FilterChip(selected = style.textColor == color, onClick = { style = style.copy(textColor = color) }, label = { Text(label) })
                    }
                }
                Text("Border / outline width: ${style.outlineWidth.toInt()}")
                Slider(value = style.outlineWidth, onValueChange = { style = style.copy(outlineWidth = it) }, valueRange = 0f..12f)
                Text("Border color")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Black" to android.graphics.Color.BLACK, "White" to android.graphics.Color.WHITE, "Blue" to android.graphics.Color.BLUE, "Red" to android.graphics.Color.RED).forEach { (label, color) ->
                        FilterChip(selected = style.outlineColor == color, onClick = { style = style.copy(outlineColor = color) }, label = { Text(label) })
                    }
                }
                Text("Font")
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("sans-serif", "sans-serif-condensed", "serif", "monospace").forEach { font ->
                        FilterChip(selected = style.font == font, onClick = { style = style.copy(font = font) }, label = { Text(font.substringBefore('-')) })
                    }
                }
                Text("In animation")
                SingleChoiceSegmentedButtonRow {
                    listOf("None", "Fade", "Pop").forEachIndexed { i, item -> SegmentedButton(selected = style.entrance == item, onClick = { style = style.copy(entrance = item) }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(item) } }
                }
                Text("Out animation")
                SingleChoiceSegmentedButtonRow {
                    listOf("None", "Fade", "Shrink").forEachIndexed { i, item -> SegmentedButton(selected = style.exit == item, onClick = { style = style.copy(exit = item) }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(item) } }
                }
                Text("Subtitle styling is rendered into the exported MP4. Fade/pop/shrink are applied at each cue's in/out edges.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { subtitleFile = File(video.parentFile, video.nameWithoutExtension + ".srt").takeIf { it.exists() } }) { Text("Reload SRT") }
                    TextButton(onClick = { subtitleFile = null }) { Text("No subtitles") }
                }
                HorizontalDivider()
                Text("OVERLAY & AUDIO TRACKS", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = overlayText, onValueChange = { overlayText = it.take(120) }, modifier = Modifier.fillMaxWidth(), label = { Text("Text overlay (burned into video)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { musicPicker.launch(arrayOf("audio/*")) }) { Text("Add music track") }
                    TextButton(onClick = { music = null }) { Text("Remove music") }
                }
                Text(music?.name ?: "No background music selected", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("EXPORT", style = MaterialTheme.typography.titleMedium)
                Button(enabled = duration > 0L && trimEnd - trimStart >= 250f && !busy, onClick = { player?.pause(); playing = false; onTrim(video, trimStart.toLong(), trimEnd.toLong(), music, subtitleFile, style, overlayText) }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Rendering…" else "Render edited MP4") }
                Text("This export re-encodes video at the selected in/out points, burns styled SRT captions and text overlay into the frames, and mixes the optional music track.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("Automatic English subtitles", style = MaterialTheme.typography.titleMedium)
                Text(if (modelReady) "Generate an SRT locally, then adjust its visual style above." else "Download the English model once from the main screen first.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Button(enabled = modelReady && !busy, onClick = { player?.pause(); playing = false; onTranscribe(video) }, modifier = Modifier.fillMaxWidth()) { Text("Generate subtitles") }
                OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, modifier = Modifier.fillMaxWidth(), label = { Text("Thumbnail title") }, singleLine = true)
                Button(enabled = duration > 0L && !busy, onClick = { player?.pause(); playing = false; onThumbnail(video, title, position) }, modifier = Modifier.fillMaxWidth()) { Text("Create 1280 × 720 thumbnail") }
                Button(onClick = { onShare(video) }, modifier = Modifier.fillMaxWidth()) { Text("Upload / share video…") }
            }
        }
    }
}

@Composable
private fun TimelineLane(label: String, detail: String, start: Long, end: Long, duration: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        val fraction = if (duration > 0) ((end - start).toFloat() / duration).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(7.dp))
    }
}

private data class Cue(val start: Long, val end: Long, val text: String)

private fun parseSrt(file: File?): List<Cue> {
    if (file == null || !file.isFile) return emptyList()
    val time = Regex("(\\d{2,}):(\\d{2}):(\\d{2}),(\\d{3})")
    fun ms(s: String): Long {
        val p = time.matchEntire(s)?.groupValues ?: return 0L
        return p[1].toLong() * 3600000 + p[2].toLong() * 60000 + p[3].toLong() * 1000 + p[4].toLong()
    }
    return file.readLines().joinToString("\n").split(Regex("\\n\\s*\\n+")).mapNotNull { block ->
        val lines = block.lines()
        val idx = lines.indexOfFirst { it.contains("-->") }
        if (idx < 0) null else {
            val times = lines[idx].split("-->").map { it.trim().split(" ").first() }
            if (times.size != 2) null else Cue(ms(times[0]), ms(times[1]), lines.drop(idx + 1).joinToString(" ").trim())
        }
    }
}

@UnstableApi
private class CaptionOverlay(
    private val cues: List<Cue>,
    private val style: SubtitleStyle,
    private val title: String
) : CanvasOverlay(true) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; this.style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        val ms = presentationTimeUs / 1000L
        val cue = cues.firstOrNull { ms >= it.start && ms <= it.end }
        if (cue != null) {
            val duration = (cue.end - cue.start).coerceAtLeast(1L).toFloat()
            val edge = 180f
            val enter = ((ms - cue.start) / edge).coerceIn(0f, 1f)
            val leave = ((cue.end - ms) / edge).coerceIn(0f, 1f)
            val alpha = when (style.exit) { "Fade" -> minOf(enter, leave); else -> if (style.entrance == "Fade") enter else 1f }.coerceIn(0f, 1f)
            val scale = when {
                style.entrance == "Pop" && ms - cue.start < edge -> .82f + .18f * enter
                style.exit == "Shrink" && cue.end - ms < edge -> .82f + .18f * leave
                else -> 1f
            }
            val size = canvas.width * style.sizeSp / 720f * scale
            fill.color = style.textColor; fill.alpha = (255 * alpha).toInt(); fill.textSize = size; fill.typeface = Typeface.create(style.font, Typeface.BOLD)
            stroke.color = style.outlineColor; stroke.alpha = (255 * alpha).toInt(); stroke.textSize = size; stroke.typeface = fill.typeface; stroke.strokeWidth = style.outlineWidth * canvas.width / 720f
            val text = cue.text.replace(Regex("<[^>]*>"), "").take(160)
            val maxWidth = canvas.width * .88f
            val words = text.split(" ")
            val lines = ArrayList<String>(); var line = ""
            words.forEach { word -> val next = if (line.isEmpty()) word else "$line $word"; if (fill.measureText(next) > maxWidth && line.isNotEmpty()) { lines.add(line); line = word } else line = next }
            if (line.isNotEmpty()) lines.add(line)
            val lineHeight = size * 1.18f
            val baseY = canvas.height * .88f - (lines.size - 1) * lineHeight
            lines.take(3).forEachIndexed { index, textLine ->
                val y = baseY + index * lineHeight
                canvas.drawText(textLine, canvas.width / 2f, y, stroke)
                canvas.drawText(textLine, canvas.width / 2f, y, fill)
            }
        }
        if (title.isNotBlank()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = canvas.width * .045f; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(5f, 2f, 2f, android.graphics.Color.BLACK) }
            canvas.drawText(title, canvas.width * .05f, canvas.height * .09f, p)
        }
    }
}

object VideoEditor {
    private val executor = Executors.newSingleThreadExecutor()

    @OptIn(UnstableApi::class)
    fun export(
        context: Context, source: File, startMs: Long, endMs: Long, music: File?,
        subtitles: File?, style: SubtitleStyle, overlayText: String,
        complete: (Result<File>) -> Unit
    ) {
        executor.execute {
            val output = File(source.parentFile, source.nameWithoutExtension + "_edit_" + System.currentTimeMillis() + ".mp4")
            val result = runCatching {
                require(source.isFile && endMs > startMs) { "Invalid video or edit range" }
                val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
                val mediaItem = MediaItem.Builder().setUri(Uri.fromFile(source)).setClippingConfiguration(clipping).build()
                val overlays = CaptionOverlay(parseSrt(subtitles), style, overlayText)
                val videoItem = EditedMediaItem.Builder(mediaItem)
                    .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlays)))))
                    .build()
                val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(videoItem))
                val composition = if (music != null && music.isFile) {
                    val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(music))).build()
                    val audioSequence = EditedMediaItemSequence.withAudioFrom(listOf(audioItem)).buildUpon().setIsLooping(true).build()
                    Composition.Builder(videoSequence, audioSequence).build()
                } else Composition.Builder(videoSequence).build()
                val latch = java.util.concurrent.CountDownLatch(1)
                var failure: Throwable? = null
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { latch.countDown() }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { failure = exportException; latch.countDown() }
                    }).build()
                transformer.start(composition, output.absolutePath)
                if (!latch.await(45, java.util.concurrent.TimeUnit.MINUTES)) {
                    transformer.cancel(); error("Export timed out")
                }
                failure?.let { throw it }
                require(output.exists() && output.length() > 0L) { "Export produced no MP4" }
                output
            }
            if (result.isFailure) output.delete()
            android.os.Handler(android.os.Looper.getMainLooper()).post { complete(result) }
        }
    }

    fun createThumbnail(video: File, title: String, positionMs: Long, complete: (Result<File>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.absolutePath)
                    val frame = retriever.getFrameAtTime(positionMs.coerceAtLeast(0L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) ?: error("Could not extract a video frame")
                    val bitmap = android.graphics.Bitmap.createScaledBitmap(frame, 1280, 720, true)
                    if (bitmap !== frame) frame.recycle()
                    val canvas = Canvas(bitmap)
                    val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(165, 4, 10, 20) }
                    canvas.drawRoundRect(android.graphics.RectF(30f, 470f, 1250f, 690f), 24f, 24f, shade)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 66f; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); setShadowLayer(8f, 2f, 3f, android.graphics.Color.BLACK) }
                    canvas.drawText(title.ifBlank { video.nameWithoutExtension }.take(48), 65f, 590f, paint)
                    val file = File(video.parentFile, video.nameWithoutExtension + "_thumbnail.jpg")
                    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                    bitmap.recycle(); file
                } finally { retriever.release() }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { complete(result) }
        }
    }
}

private fun clock(ms: Long): String {
    val sec = ms.coerceAtLeast(0L) / 1000L
    return "%02d:%02d".format(sec / 60L, sec % 60L)
}
