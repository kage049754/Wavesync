package com.wavesync.app

import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.provider.MediaStore
import androidx.core.content.FileProvider
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF090D16)
private val Card = Color(0xFF151E2D)
private val Cyan = Color(0xFF61E6D2)
private val Purple = Color(0xFF9B8CFF)
private val Muted = Color(0xFF9AA8BC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9,13,22)
        window.navigationBarColor = android.graphics.Color.rgb(9,13,22)
        setContent { ClipForgeApp(this) }
    }
}

@Composable
private fun ClipForgeApp(activity: MainActivity) {
    var fps by remember { mutableIntStateOf(60) }
    var quality by remember { mutableStateOf("1080p") }
    var microphoneEnabled by remember { mutableStateOf(false) }
    var playbackAudioEnabled by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready to capture your screen") }
    var recordings by remember { mutableStateOf(emptyList<File>()) }
    var modelReady by remember { mutableStateOf(OfflineTranscriber.isModelInstalled(activity)) }
    var asrBusy by remember { mutableStateOf(false) }
    var asrStatus by remember { mutableStateOf(if (modelReady) "Offline English model is ready" else "Download the 40 MB English model once to enable offline transcription") }
    var editingSubtitle by remember { mutableStateOf<File?>(null) }
    var editingVideo by remember { mutableStateOf<File?>(null) }
    var importVideoPending by remember { mutableStateOf(false) }
    var subtitleDraft by remember { mutableStateOf("") }
    var subtitleImportTarget by remember { mutableStateOf<File?>(null) }
    val refreshRecordings = {
        val folder = File(activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ClipForge")
        recordings = folder.listFiles { file -> file.isFile && file.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    LaunchedEffect(Unit) { refreshRecordings() }
    DisposableEffect(activity) {
        val prefs = activity.getSharedPreferences("clipforge", Context.MODE_PRIVATE)
        val updateStatus = {
            val lastError = prefs.getString("last_error", "").orEmpty()
            val muxStatus = prefs.getString("last_mux_status", "").orEmpty()
            if (lastError.isNotBlank()) status = "Recording issue: " + lastError
            else if (muxStatus.isNotBlank()) status = muxStatus
        }
        val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "last_mux_status" || key == "last_error" || key == "recording_active") {
                activity.runOnUiThread {
                    isRecording = prefs.getBoolean("recording_active", false)
                    updateStatus()
                    if (!isRecording) refreshRecordings()
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshRecordings()
                isRecording = prefs.getBoolean("recording_active", false)
                updateStatus()
                if (prefs.getString("last_mux_status", "").orEmpty().isBlank() &&
                    prefs.getString("last_error", "").orEmpty().isBlank() &&
                    !isRecording && prefs.getString("last_recording", "").orEmpty().isNotBlank()) {
                    status = "Recording saved. Your library is up to date."
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        activity.lifecycle.addObserver(observer)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            activity.lifecycle.removeObserver(observer)
        }
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val intent = Intent(activity, ScreenRecordingService::class.java).apply {
                action = ScreenRecordingService.ACTION_START
                putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenRecordingService.EXTRA_FPS, fps)
                putExtra(ScreenRecordingService.EXTRA_QUALITY, quality)
                putExtra(ScreenRecordingService.EXTRA_MIC, microphoneEnabled)
                putExtra(ScreenRecordingService.EXTRA_PLAYBACK, playbackAudioEnabled)
            }
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
            isRecording = true
            status = "Recording started. Use the floating controls over other apps."
        } else status = "Screen capture permission was not granted"
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneEnabled = granted
        status = if (granted) "Microphone audio enabled for the next recording" else "Microphone permission was denied"
    }
    val playbackAudioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        playbackAudioEnabled = granted
        status = if (granted) "App playback audio enabled for the next recording" else "Audio permission was denied"
    }
    val videoImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val folder = File(activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ClipForge").apply { mkdirs() }
                val imported = File(folder, "Imported_" + System.currentTimeMillis() + ".mp4")
                activity.contentResolver.openInputStream(uri)?.use { input -> imported.outputStream().use { output -> input.copyTo(output) } }
                    ?: error("Selected video could not be opened")
                require(imported.length() > 0L) { "Selected video is empty" }
                refreshRecordings()
                editingVideo = imported
                status = "Video imported into ClipForge. Open its editor to trim, caption, make a thumbnail or share it."
            }.onFailure { status = "Video import failed: " + (it.message ?: "unknown error") }
        }
    }
    val subtitleImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val video = subtitleImportTarget
        if (uri != null && video != null) {
            runCatching {
                val destination = File(video.parentFile, video.nameWithoutExtension + ".srt")
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: error("The selected subtitle file could not be opened")
                subtitleDraft = destination.readText(Charsets.UTF_8)
                editingSubtitle = destination
                status = "SRT imported. Review it before saving."
            }.onFailure { status = "SRT import failed: " + (it.message ?: "unknown error") }
        }
        subtitleImportTarget = null
        refreshRecordings()
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Purple, background = Bg, surface = Card)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF142238), Bg, Bg))),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = Cyan.copy(alpha=.15f)) {
                        Icon(Icons.Rounded.Movie, null, tint=Cyan, modifier=Modifier.padding(10.dp).size(27.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ClipForge", fontSize=25.sp, fontWeight=FontWeight.Bold)
                        Text("RECORD • TRANSCRIBE • EDIT", color=Muted, fontSize=9.sp, letterSpacing=1.5.sp)
                    }
                    Surface(color=Purple.copy(alpha=.15f), shape=RoundedCornerShape(20.dp)) {
                        Text("BETA", color=Purple, fontSize=10.sp, fontWeight=FontWeight.Bold, modifier=Modifier.padding(horizontal=10.dp, vertical=6.dp))
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF233B54), Color(0xFF1B2038))), RoundedCornerShape(24.dp)).padding(20.dp)) {
                    Text(if (isRecording) "RECORDING" else "SCREEN RECORDER", color=if(isRecording) Color(0xFFFF7777) else Cyan, fontSize=11.sp, fontWeight=FontWeight.Bold, letterSpacing=1.4.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(if (isRecording) "Capture in progress" else "Capture streams and gameplay", fontSize=22.sp, fontWeight=FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("1080p and 60 FPS are requested when supported by your display and hardware encoder.", color=Muted, fontSize=12.sp, lineHeight=18.sp)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Button(onClick={
                            if (!isRecording) {
                                if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(activity)) {
                                    status = "Allow ClipForge to display the floating recording controls, then tap Start again."
                                    activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + activity.packageName)))
                                } else if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                                }
                            } else {
                                activity.startService(Intent(activity, ScreenRecordingService::class.java).setAction(ScreenRecordingService.ACTION_STOP))
                                isRecording=false
                                status="Stop requested. Your video is being finalized."
                            }
                        }, modifier=Modifier.weight(1f).height(52.dp), colors=ButtonDefaults.buttonColors(containerColor=if(isRecording) Color(0xFFB84D5B) else Cyan, contentColor=Bg), shape=RoundedCornerShape(15.dp)) {
                            Icon(if(isRecording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord, null)
                            Spacer(Modifier.width(8.dp)); Text(if(isRecording) "Stop recording" else "Start recording", fontWeight=FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(status, color=Muted, fontSize=11.sp)
                }
            }
            item {
                SectionTitle("Recording quality", "CAPTURE")
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Resolution", color=Muted, fontSize=12.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("720p","1080p").forEach { v -> FilterChip(selected=quality==v,onClick={quality=v},label={Text(v)}) }
                    }
                    Text("Frame rate", color=Muted, fontSize=12.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf(30,60).forEach { v -> FilterChip(selected=fps==v,onClick={fps=v},label={Text("$v FPS")}) }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Microphone audio", fontSize=13.sp, fontWeight=FontWeight.SemiBold)
                            Text("Record nearby voice from the microphone.", color=Muted, fontSize=10.sp)
                        }
                        Switch(checked=microphoneEnabled, onCheckedChange={ enabled ->
                            if (enabled) microphonePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            else microphoneEnabled=false
                        })
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("App playback audio", fontSize=13.sp, fontWeight=FontWeight.SemiBold)
                            Text("Android 10+. Captured audio is added to the MP4 after recording; some apps block capture.", color=Muted, fontSize=10.sp)
                        }
                        Switch(checked=playbackAudioEnabled, onCheckedChange={ enabled ->
                            if (enabled && Build.VERSION.SDK_INT < 29) {
                                status = "App audio capture requires Android 10 or newer"
                            } else if (enabled && activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                playbackAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            } else playbackAudioEnabled = enabled
                        })
                    }
                    if (microphoneEnabled && playbackAudioEnabled) {
                        Text("Microphone and captured app audio are saved as separate MP4 audio tracks, not mixed together.", color=Muted, fontSize=10.sp, lineHeight=14.sp)
                    }
                }
            }
            item {
                SectionTitle("Automatic subtitles", "AI TRANSCRIPTION")
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ClosedCaption, tint=Cyan, contentDescription=null, modifier=Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Offline English transcription", fontWeight=FontWeight.SemiBold)
                    }
                    Text("Download the small Vosk English model once. After installation, saved recordings can be transcribed on-device without sending audio to a server. The first version exports timestamped SRT subtitles.", color=Muted, fontSize=11.sp, lineHeight=17.sp)
                    Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(enabled=!asrBusy && !modelReady, onClick={
                            asrBusy=true
                            asrStatus="Connecting to the model download..."
                            OfflineTranscriber.downloadModel(activity,{ message -> asrStatus=message }) { result ->
                                asrBusy=false
                                result.onSuccess { modelReady=true; asrStatus="English model installed. Transcription is now offline." }
                                    .onFailure { asrStatus="Model download failed: " + (it.message ?: "unknown error") }
                            }
                        }) { Text(if (modelReady) "Model installed" else if (asrBusy) "Please wait..." else "Download English model") }
                        if (modelReady) Icon(Icons.Rounded.CheckCircle, contentDescription="Model installed", tint=Cyan)
                    }
                    Text(asrStatus, color=if (modelReady) Cyan else Muted, fontSize=10.sp, lineHeight=15.sp)
                }
            }
            item {
                SectionTitle("Video editor", "EDIT • CAPTION • THUMBNAIL")
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Import a video or choose one from your library. Play it here, trim a section, generate English subtitles, create a thumbnail and share the finished video.", color=Muted, fontSize=11.sp, lineHeight=17.sp)
                    Button(onClick={ videoImportLauncher.launch(arrayOf("video/mp4", "video/*")) }, modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Import video to ClipForge")
                    }
                    Text("Editing and subtitle generation happen on this device. You can share the exported MP4 to YouTube or another app using Android's upload/share chooser.", color=Muted, fontSize=10.sp, lineHeight=15.sp)
                }
            }
            item {
                SectionTitle("Saved recordings", "LIBRARY")
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(recordings.size.toString() + " video file(s)", color=Muted, fontSize=11.sp, modifier=Modifier.weight(1f))
                        TextButton(onClick={ refreshRecordings() }) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Refresh") }
                    }
                    if (recordings.isEmpty()) {
                        Text("No recordings yet. Start a screen capture to create your first MP4.", color=Muted, fontSize=11.sp)
                    } else {
                        recordings.forEach { file ->
                            Row(verticalAlignment=Alignment.CenterVertically, modifier=Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.VideoFile, tint=Cyan, contentDescription=null, modifier=Modifier.size(25.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, fontWeight=FontWeight.SemiBold, fontSize=12.sp)
                                    Text(android.text.format.Formatter.formatShortFileSize(activity, file.length()) + " • " + java.text.SimpleDateFormat("MMM d, yyyy  h:mm a", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified())), color=Muted, fontSize=10.sp)
                                }
                                IconButton(onClick = { editingVideo = file }) { Icon(Icons.Rounded.PlayArrow, contentDescription="Play and edit video", tint=Cyan) }
                                val playbackAudio = File(file.parentFile, file.nameWithoutExtension + "_audio.wav")
                                if (playbackAudio.exists() && playbackAudio.length() > 44L) {
                                    IconButton(onClick = {
                                        runCatching {
                                            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", playbackAudio)
                                            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/wav").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            activity.startActivity(view)
                                        }.onFailure { status = "No WAV audio player found" }
                                    }) { Icon(Icons.Rounded.GraphicEq, contentDescription="Play captured app audio", tint=Purple) }
                                }
                                TextButton(enabled=modelReady && !asrBusy, onClick={
                                    asrBusy=true
                                    asrStatus="Preparing audio decoder..."
                                    OfflineTranscriber.transcribe(activity,file,{ message -> asrStatus=message }) { result ->
                                        asrBusy=false
                                        result.onSuccess { subtitle -> asrStatus="SRT created: " + subtitle.name }
                                            .onFailure { asrStatus="Transcription failed: " + (it.message ?: "unknown error") }
                                    }
                                }) { Text("SRT", color=if(modelReady && !asrBusy) Cyan else Muted, fontSize=11.sp) }
                                if (File(file.parentFile, file.nameWithoutExtension + ".srt").exists()) {
                                    IconButton(onClick={
                                        val subtitle = File(file.parentFile, file.nameWithoutExtension + ".srt")
                                        subtitleDraft = subtitle.readText()
                                        editingSubtitle = subtitle
                                    }) { Icon(Icons.Rounded.Edit, contentDescription="Edit subtitles", tint=Purple) }
                                }
                                TextButton(onClick={
                                    subtitleImportTarget = file
                                    subtitleImportLauncher.launch(arrayOf("application/x-subrip", "text/plain", "application/octet-stream"))
                                }) { Text("Import SRT", color=Muted, fontSize=10.sp) }
                                val subtitleFile = File(file.parentFile, file.nameWithoutExtension + ".srt")
                                if (subtitleFile.exists()) {
                                    IconButton(onClick={
                                        runCatching {
                                            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", subtitleFile)
                                            val share = Intent(Intent.ACTION_SEND).setType("application/x-subrip")
                                                .putExtra(Intent.EXTRA_STREAM, uri)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            activity.startActivity(Intent.createChooser(share, "Export subtitles"))
                                        }.onFailure { status = "SRT export failed: " + (it.message ?: "unknown error") }
                                    }) { Icon(Icons.Rounded.Share, contentDescription="Share or export SRT", tint=Cyan) }
                                }
                                IconButton(onClick = {
                                    runCatching {
                                        val values = ContentValues().apply {
                                            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                                            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ClipForge")
                                        }
                                        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                        val uri = activity.contentResolver.insert(collection, values) ?: error("Gallery entry could not be created")
                                        try {
                                            activity.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
                                                ?: error("Gallery output stream unavailable")
                                            status = "Saved " + file.name + " to Movies/ClipForge"
                                        } catch (e: Exception) {
                                            activity.contentResolver.delete(uri, null, null)
                                            throw e
                                        }
                                    }.onFailure { status = "Gallery export failed: " + (it.message ?: "unknown error") }
                                }) { Icon(Icons.Rounded.SaveAlt, contentDescription="Save to gallery", tint=Purple) }
                            }
                        }
                    }
                    Text("Original MP4s stay in app storage. When an internal-audio WAV sidecar exists, SRT transcription uses that captured app audio first; otherwise it uses an audio track in the MP4.", color=Muted, fontSize=10.sp, lineHeight=15.sp)
                }
            }
            item {
                Text("Capture depends on Android MediaProjection approval. Some streaming apps block playback-audio capture. Screen recording is not guaranteed to sustain 1080p at 60 FPS on every device.", color=Muted, fontSize=10.sp, lineHeight=15.sp)
            }
        }
        if (editingVideo != null) {
            VideoEditorDialog(
                video = editingVideo!!,
                modelReady = modelReady,
                busy = asrBusy,
                onDismiss = { editingVideo = null },
                onTranscribe = { video ->
                    asrBusy = true
                    asrStatus = "Preparing video audio for automatic subtitles..."
                    OfflineTranscriber.transcribe(activity, video, { message -> asrStatus = message }) { result ->
                        asrBusy = false
                        result.onSuccess { subtitle ->
                            asrStatus = "Automatic subtitles created: " + subtitle.name
                            status = "English subtitles generated. Open Edit subtitles in the library to correct the text."
                            refreshRecordings()
                        }.onFailure { asrStatus = "Transcription failed: " + (it.message ?: "unknown error") }
                    }
                },
                onTrim = { video, startMs, endMs ->
                    status = "Exporting trimmed clip..."
                    VideoEditor.trim(video, startMs, endMs) { result ->
                        result.onSuccess { trimmed ->
                            status = "Trimmed clip saved: " + trimmed.name
                            refreshRecordings()
                            editingVideo = trimmed
                        }.onFailure { status = "Trim failed: " + (it.message ?: "unknown error") }
                    }
                },
                onThumbnail = { video, title ->
                    VideoEditor.createThumbnail(video, title) { result ->
                        result.onSuccess { status = "Thumbnail saved: " + it.name }
                            .onFailure { status = "Thumbnail failed: " + (it.message ?: "unknown error") }
                    }
                },
                onShare = { video ->
                    runCatching {
                        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", video)
                        val share = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        activity.startActivity(Intent.createChooser(share, "Upload or share video"))
                    }.onFailure { status = "Could not open upload/share chooser: " + (it.message ?: "unknown error") }
                }
            )
        }
        if (editingSubtitle != null) {
            AlertDialog(
                onDismissRequest={ editingSubtitle=null },
                title={ Text("Edit subtitle file") },
                text={
                    OutlinedTextField(
                        value=subtitleDraft,
                        onValueChange={ subtitleDraft=it },
                        modifier=Modifier.fillMaxWidth().heightIn(min=180.dp,max=420.dp),
                        minLines=8,
                        maxLines=16,
                        label={ Text("SRT subtitles and timestamps") }
                    )
                },
                confirmButton={
                    TextButton(onClick={
                        runCatching {
                            validateSrt(subtitleDraft)
                            editingSubtitle?.writeText(subtitleDraft, Charsets.UTF_8)
                                ?: error("No subtitle file selected")
                        }
                            .onSuccess { status="Subtitle changes saved"; editingSubtitle=null }
                            .onFailure { status="Subtitle not saved: " + (it.message ?: "invalid SRT") }
                    }) { Text("Validate and save") }
                },
                dismissButton={ TextButton(onClick={editingSubtitle=null}) { Text("Cancel") } }
            )
        }
    }
}

private fun validateSrt(content: String) {
    val normalized = content.replace("\r\n", "\n").trim()
    require(normalized.isNotEmpty()) { "Subtitle file is empty" }
    val blocks = normalized.split(Regex("\\n\\s*\\n+"))
    val timing = Regex("^(\\d{2,}:\\d{2}:\\d{2},\\d{3})\\s+-->\\s+(\\d{2,}:\\d{2}:\\d{2},\\d{3})$")
    blocks.forEachIndexed { index, block ->
        val lines = block.lines().filter { it.isNotBlank() }
        require(lines.size >= 3) { "Subtitle block ${index + 1} needs a number, timestamp and text" }
        require(lines.first().trim().toIntOrNull() != null) { "Subtitle block ${index + 1} has an invalid cue number" }
        val match = timing.matchEntire(lines[1].trim())
            ?: throw IllegalArgumentException("Subtitle block ${index + 1} has an invalid timestamp")
        fun toMillis(value: String): Long {
            val parts = value.split(':', ',')
            return parts[0].toLong() * 3_600_000 + parts[1].toLong() * 60_000 +
                parts[2].toLong() * 1_000 + parts[3].toLong()
        }
        require(toMillis(match.groupValues[2]) > toMillis(match.groupValues[1])) {
            "Subtitle block ${index + 1} ends before it starts"
        }
    }
}

@Composable private fun SectionTitle(title:String, tag:String) {
    Row(verticalAlignment=Alignment.CenterVertically) {
        Text(title, fontSize=18.sp, fontWeight=FontWeight.Bold)
        Spacer(Modifier.weight(1f)); Text(tag, color=Purple, fontSize=9.sp, fontWeight=FontWeight.Bold, letterSpacing=1.sp)
    }
}
@Composable private fun EditorFeature(icon: androidx.compose.ui.graphics.vector.ImageVector, title:String, detail:String) {
    Row(verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=Cyan,modifier=Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column { Text(title,fontWeight=FontWeight.SemiBold,fontSize=13.sp); Text(detail,color=Muted,fontSize=10.sp) }
    }
}