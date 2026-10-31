package com.wavesync.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

object OfflineTranscriber {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_FOLDER = "vosk-model-small-en-us-0.15"
    private val main = Handler(Looper.getMainLooper())

    fun modelDirectory(context: Context) = File(File(context.filesDir, "speech-models"), MODEL_FOLDER)
    fun isModelInstalled(context: Context) = File(modelDirectory(context), "am/final.mdl").exists()

    fun downloadModel(context: Context, progress: (String) -> Unit, complete: (Result<Unit>) -> Unit) {
        Thread {
            val result = runCatching {
                val root = File(context.filesDir, "speech-models").apply { mkdirs() }
                val staging = File(root, "model-download.tmp").apply { deleteRecursively(); mkdirs() }
                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    connect()
                }
                try {
                    val total = connection.contentLength
                    ZipInputStream(connection.inputStream.buffered()).use { zip ->
                        val buffer = ByteArray(16 * 1024)
                        var extracted = 0L
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val destination = File(staging, entry.name)
                            val safeRoot = staging.canonicalPath + File.separator
                            require(destination.canonicalPath.startsWith(safeRoot) || destination.canonicalPath == staging.canonicalPath) { "Invalid model archive path" }
                            if (entry.isDirectory) destination.mkdirs() else {
                                destination.parentFile?.mkdirs()
                                FileOutputStream(destination).use { output ->
                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count <= 0) break
                                        output.write(buffer, 0, count)
                                        extracted += count
                                        if (total > 0 && extracted % (1024 * 1024) < buffer.size) {
                                            val percent = (extracted * 100 / total).coerceIn(0, 99)
                                            main.post { progress("Downloading English model: $percent%") }
                                        }
                                    }
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                    val extractedModel = File(staging, MODEL_FOLDER)
                    require(File(extractedModel, "am/final.mdl").exists()) { "Downloaded model is incomplete" }
                    val target = modelDirectory(context)
                    target.deleteRecursively()
                    check(extractedModel.renameTo(target)) { "Could not install downloaded model" }
                    staging.deleteRecursively()
                    Unit
                } finally { connection.disconnect() }
            }
            main.post { complete(result) }
        }.start()
    }

    fun transcribe(context: Context, video: File, progress: (String) -> Unit, complete: (Result<File>) -> Unit) {
        Thread {
            val result = runCatching {
                require(isModelInstalled(context)) { "Download the English model first" }
                val output = File(video.parentFile, video.nameWithoutExtension + ".srt")
                val model = Model(modelDirectory(context).absolutePath)
                try {
                    val words = mutableListOf<Word>()
                    Recognizer(model, 16000.0f).use { recognizer ->
                        decodeAudio(video, recognizer, words, progress)
                        collectWords(recognizer.finalResult, words)
                    }
                    require(words.isNotEmpty()) { "No speech was detected in this recording" }
                    output.writeText(toSrt(words), Charsets.UTF_8)
                } finally { model.close() }
                output
            }
            main.post { complete(result) }
        }.start()
    }

    private data class Word(val text: String, val start: Double, val end: Double)

    private fun decodeAudio(video: File, recognizer: Recognizer, words: MutableList<Word>, progress: (String) -> Unit) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(video.absolutePath)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if ((format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; break }
            }
            require(track >= 0) { "No audio track found. This recording may contain video only." }
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio codec")
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = 2
            var frameIndex = 0L
            var nextOutputFrame = 0.0
            val pending = ByteArrayOutputStream()
            var lastProgress = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(outputIndex)!!.duplicate().order(ByteOrder.nativeOrder())
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytesPerSample = if (pcmEncoding == 4) 4 else 2
                            val frameBytes = channels * bytesPerSample
                            while (buffer.remaining() >= frameBytes) {
                                var mono = 0.0
                                repeat(channels) { mono += if (pcmEncoding == 4) buffer.float.toDouble() else buffer.short / 32768.0 }
                                mono = (mono / channels).coerceIn(-1.0, 1.0)
                                if (frameIndex.toDouble() + 0.0001 >= nextOutputFrame) {
                                    val sample = (mono * 32767.0).toInt().coerceIn(-32768, 32767)
                                    pending.write(sample and 0xff)
                                    pending.write((sample shr 8) and 0xff)
                                    nextOutputFrame += sampleRate / 16000.0
                                }
                                frameIndex++
                            }
                            if (pending.size() >= 32768) {
                                val pcm = pending.toByteArray()
                                val count = pcm.size - pcm.size % 2
                                if (count > 0 && recognizer.acceptWaveForm(pcm, count)) collectWords(recognizer.result, words)
                                pending.reset()
                            }
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val seconds = info.presentationTimeUs / 1000000L
                        if (seconds - lastProgress >= 5) {
                            lastProgress = seconds
                            main.post { progress("Transcribing audio: ${seconds}s processed") }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            val tail = pending.toByteArray()
            if (tail.isNotEmpty() && recognizer.acceptWaveForm(tail, tail.size)) collectWords(recognizer.result, words)
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun collectWords(json: String, destination: MutableList<Word>) {
        val array = JSONObject(json).optJSONArray("result") ?: return
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val word = item.optString("word").trim()
            if (word.isNotEmpty()) destination.add(Word(word, item.optDouble("start", 0.0), item.optDouble("end", 0.0)))
        }
    }

    private fun toSrt(words: List<Word>): String {
        val blocks = mutableListOf<String>()
        var group = mutableListOf<Word>()
        fun flush() {
            if (group.isEmpty()) return
            val text = group.joinToString(" ") { it.text }
            blocks.add("${timestamp(group.first().start)} --> ${timestamp(group.last().end)}\n$text")
            group = mutableListOf()
        }
        for (word in words) {
            if (group.isNotEmpty() && (group.size >= 8 || word.end - group.first().start > 4.0)) flush()
            group.add(word)
        }
        flush()
        return blocks.mapIndexed { index, block -> "${index + 1}\n$block" }.joinToString("\n\n") + "\n"
    }

    private fun timestamp(seconds: Double): String {
        val millis = (seconds * 1000).toLong().coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(millis / 3600000, (millis % 3600000) / 60000, (millis % 60000) / 1000, millis % 1000)
    }
}
