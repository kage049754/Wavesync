package com.wavesync.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val YOUTUBE_UPLOAD_SCOPE = "https://www.googleapis.com/auth/youtube.upload"

@Composable
fun YouTubeUploadDialog(video: File, onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var title by remember(video.absolutePath) { mutableStateOf(video.nameWithoutExtension.take(100)) }
    var description by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf("private") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Authorize your Google account to upload directly to YouTube.") }
    var pendingUpload by remember { mutableStateOf(false) }

    val authorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data!!) }
                .onSuccess { auth ->
                    val token = auth.accessToken
                    if (token.isNullOrBlank()) {
                        message = "Google authorization returned no access token."
                        busy = false
                    } else if (pendingUpload) {
                        pendingUpload = false
                        startYouTubeUpload(video, token, title, description, privacy) { status ->
                            message = status
                            if (status.startsWith("Upload complete")) busy = false
                        }
                    } else {
                        message = "Google account authorized. Select Upload to send this video."
                        busy = false
                    }
                }.onFailure {
                    message = "Authorization failed: ${it.message ?: "unknown error"}"
                    busy = false
                }
        } else {
            busy = false
            pendingUpload = false
            message = "Google authorization was cancelled."
        }
    }

    fun authorize(uploadAfter: Boolean) {
        val host = activity ?: run { message = "Activity unavailable"; return }
        busy = true
        pendingUpload = uploadAfter
        message = "Requesting YouTube upload permission..."
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(YOUTUBE_UPLOAD_SCOPE)))
            .build()
        Identity.getAuthorizationClient(host).authorize(request)
            .addOnSuccessListener { auth ->
                if (auth.hasResolution()) {
                    val pending = auth.pendingIntent
                    if (pending == null) {
                        busy = false
                        message = "Google did not provide an authorization screen."
                    } else {
                        authorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    }
                } else {
                    val token = auth.accessToken
                    if (token.isNullOrBlank()) {
                        busy = false
                        message = "Google authorization returned no access token."
                    } else if (uploadAfter) {
                        pendingUpload = false
                        startYouTubeUpload(video, token, title, description, privacy) { status ->
                            message = status
                            if (status.startsWith("Upload complete")) busy = false
                        }
                    } else {
                        busy = false
                        message = "Google account authorized. You can now upload."
                    }
                }
            }.addOnFailureListener {
                busy = false
                pendingUpload = false
                message = "Authorization unavailable: ${it.message ?: "check Google Play services and OAuth setup"}"
            }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.88f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Upload to YouTube", style = MaterialTheme.typography.titleLarge)
                Text(video.name, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(title, { title = it.take(100) }, modifier = Modifier.fillMaxWidth(), label = { Text("Video title") }, singleLine = true)
                OutlinedTextField(description, { description = it.take(5000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Description") }, minLines = 3)
                Text("Visibility")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("private", "unlisted", "public").forEachIndexed { i, value ->
                        SegmentedButton(selected = privacy == value, onClick = { privacy = value }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(value.replaceFirstChar { it.uppercase() }) }
                    }
                }
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = !busy && title.isNotBlank(), onClick = { authorize(true) }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Working..." else "Authorize and upload") }
                OutlinedButton(enabled = !busy, onClick = { authorize(false) }, modifier = Modifier.fillMaxWidth()) { Text("Connect Google account") }
                TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                Text("Uploads use YouTube Data API v3 resumable upload. The access token is held in memory only and is not saved to disk.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val youtubeExecutor = Executors.newSingleThreadExecutor()

private fun startYouTubeUpload(
    file: File, token: String, title: String, description: String, privacy: String,
    report: (String) -> Unit
) {
    youtubeExecutor.execute {
        try {
            require(file.isFile && file.length() > 0L) { "Video file is missing or empty" }
            val metadata = JSONObject().put("snippet", JSONObject()
                .put("title", title.take(100))
                .put("description", description.take(5000))
                .put("categoryId", "22"))
                .put("status", JSONObject().put("privacyStatus", privacy).put("selfDeclaredMadeForKids", false))
            val init = URL("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status").openConnection() as HttpURLConnection
            init.requestMethod = "POST"
            init.doOutput = true
            init.connectTimeout = 30000
            init.readTimeout = 30000
            init.setRequestProperty("Authorization", "Bearer $token")
            init.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            init.setRequestProperty("X-Upload-Content-Type", "video/mp4")
            init.setRequestProperty("X-Upload-Content-Length", file.length().toString())
            init.outputStream.use { it.write(metadata.toString().toByteArray(Charsets.UTF_8)) }
            val initCode = init.responseCode
            if (initCode !in 200..299) {
                val error = init.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("YouTube session request failed ($initCode): ${error.take(500)}")
            }
            val uploadUrl = init.getHeaderField("Location") ?: error("YouTube did not return an upload session URL")
            init.disconnect()

            val upload = URL(uploadUrl).openConnection() as HttpURLConnection
            upload.requestMethod = "PUT"
            upload.doOutput = true
            upload.connectTimeout = 30000
            upload.readTimeout = 120000
            upload.setFixedLengthStreamingMode(file.length())
            upload.setRequestProperty("Authorization", "Bearer $token")
            upload.setRequestProperty("Content-Type", "video/mp4")
            upload.setRequestProperty("Content-Length", file.length().toString())
            upload.setRequestProperty("Content-Range", "bytes 0-${file.length() - 1}/${file.length()}")
            file.inputStream().use { input -> upload.outputStream.use { output -> input.copyTo(output, 1024 * 1024) } }
            val code = upload.responseCode
            val response = (if (code in 200..299) upload.inputStream else upload.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("YouTube upload failed ($code): ${response.take(600)}")
            val id = JSONObject(response).optString("id")
            report("Upload complete. Video ID: $id")
            upload.disconnect()
        } catch (e: Exception) {
            report("Upload failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
