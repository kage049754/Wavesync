package com.wavesync.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
    var isRecording by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready to capture your screen") }
    var recordings by remember { mutableStateOf(emptyList<File>()) }
    val refreshRecordings = {
        val folder = File(activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ClipForge")
        recordings = folder.listFiles { file -> file.isFile && file.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    LaunchedEffect(Unit) { refreshRecordings() }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val intent = Intent(activity, ScreenRecordingService::class.java).apply {
                action = ScreenRecordingService.ACTION_START
                putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenRecordingService.EXTRA_FPS, fps)
                putExtra(ScreenRecordingService.EXTRA_QUALITY, quality)
                putExtra(ScreenRecordingService.EXTRA_MIC, false)
            }
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
            isRecording = true
            status = "Recording started. Use the notification to stop."
        } else status = "Screen capture permission was not granted"
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
                                val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                projectionLauncher.launch(manager.createScreenCaptureIntent())
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
                }
            }
            item {
                SectionTitle("Automatic subtitles", "AI TRANSCRIPTION")
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ClosedCaption, tint=Cyan, contentDescription=null, modifier=Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Live English captions + correction pass", fontWeight=FontWeight.SemiBold)
                    }
                    Text("Planned pipeline: transcribe while recording, then reprocess the saved audio for better wording and timestamps. The current recorder foundation saves the video; transcription engine integration is the next build stage.", color=Muted, fontSize=11.sp, lineHeight=17.sp)
                    Text("Subtitles will remain editable and exportable as SRT.", color=Cyan, fontSize=11.sp, fontWeight=FontWeight.SemiBold)
                }
            }
            item {
                SectionTitle("Clip editor", "POST-PRODUCTION")
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    EditorFeature(Icons.Rounded.ContentCut,"Trim and split","Cut unwanted sections and create short clips")
                    EditorFeature(Icons.Rounded.TextFields,"Subtitle styling","Move captions; change font, size, color, outline and animation")
                    EditorFeature(Icons.Rounded.Movie,"Outro and overlays","Add an ending card, image, text or music")
                    EditorFeature(Icons.Rounded.Subtitles,"Subtitle correction","Edit transcript text and timing before export")
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
                            }
                        }
                    }
                    Text("Stored privately in ClipForge's app-specific Movies folder. Transcription and editing are not implemented yet.", color=Muted, fontSize=10.sp, lineHeight=15.sp)
                }
            }
            item {
                Text("Capture depends on Android MediaProjection approval. Some streaming apps block playback-audio capture. Screen recording is not guaranteed to sustain 1080p at 60 FPS on every device.", color=Muted, fontSize=10.sp, lineHeight=15.sp)
            }
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