package com.wavesync.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts the playback-capture PCM WAV sidecar to AAC and adds it as an audio
 * track to a copy of the original MP4. The source MP4 is only replaced after
 * the completed muxed file has been validated and atomically staged.
 */
object PlaybackAudioMuxer {
    fun mux(video: File, wav: File): Result<Unit> = runCatching {
        require(video.isFile && video.length() > 0L) { "Original video is missing" }
        require(wav.isFile && wav.length() > 44L) { "Captured playback audio is empty" }
        val encodedAudio = File(video.parentFile, video.nameWithoutExtension + "_audio_encoded.mp4")
        val merged = File(video.parentFile, video.nameWithoutExtension + "_muxed.mp4")
        val backup = File(video.parentFile, video.nameWithoutExtension + "_original_backup.mp4")
        try {
            encodeWavToAac(wav, encodedAudio)
            combineTracks(video, encodedAudio, merged)
            require(merged.isFile && merged.length() > 0L) { "Muxed video output is empty" }
            MediaExtractor().useExtractor { extractor ->
                extractor.setDataSource(merged.absolutePath)
                require((0 until extractor.trackCount).any {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }) { "Muxed output has no video track" }
                require((0 until extractor.trackCount).any {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }) { "Muxed output has no audio track" }
            }
            check(video.renameTo(backup)) { "Could not stage original recording safely" }
            if (!merged.renameTo(video)) {
                backup.renameTo(video)
                error("Could not replace recording with muxed output; original was restored")
            }
            backup.delete()
        } finally {
            encodedAudio.delete()
            merged.delete()
            if (backup.exists() && !video.exists()) backup.renameTo(video)
        }
    }

    private fun encodeWavToAac(wav: File, output: File) {
        wav.inputStream().buffered().use { input ->
            val header = ByteArray(44)
            require(input.read(header) == header.size) { "Playback WAV header is incomplete" }
            fun ascii(offset: Int, count: Int) = String(header, offset, count, Charsets.US_ASCII)
            fun u16(offset: Int) = (header[offset].toInt() and 0xff) or ((header[offset + 1].toInt() and 0xff) shl 8)
            fun i32(offset: Int) = (header[offset].toInt() and 0xff) or
                ((header[offset + 1].toInt() and 0xff) shl 8) or
                ((header[offset + 2].toInt() and 0xff) shl 16) or
                ((header[offset + 3].toInt() and 0xff) shl 24)
            require(ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE" && ascii(12, 4) == "fmt " &&
                u16(20) == 1 && u16(22) == 2 && i32(24) == 44100 && u16(34) == 16 && ascii(36, 4) == "data") {
                "Unsupported playback WAV format"
            }
            val remainingBytes = i32(40).coerceAtLeast(0)
            require(remainingBytes > 0) { "Captured playback WAV contains no audio samples" }

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            var muxer: MediaMuxer? = null
            var muxerStarted = false
            var trackIndex = -1
            try {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                val info = MediaCodec.BufferInfo()
                val pcmBuffer = ByteArray(16384)
                var left = remainingBytes
                var inputFinished = false
                var outputFinished = false
                var framesQueued = 0L
                while (!outputFinished) {
                    if (!inputFinished) {
                        val inputIndex = encoder.dequeueInputBuffer(10000)
                        if (inputIndex >= 0) {
                            val codecInput = encoder.getInputBuffer(inputIndex)!!
                            codecInput.clear()
                            if (left > 0) {
                                val wanted = minOf(codecInput.remaining(), pcmBuffer.size, left)
                                val count = input.read(pcmBuffer, 0, wanted)
                                if (count <= 0) {
                                    encoder.queueInputBuffer(inputIndex, 0, 0, framesQueued * 1_000_000L / 44100L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputFinished = true
                                } else {
                                    val aligned = count - count % 4
                                    if (aligned == 0) {
                                        encoder.queueInputBuffer(inputIndex, 0, 0, framesQueued * 1_000_000L / 44100L, 0)
                                    } else {
                                        codecInput.put(pcmBuffer, 0, aligned)
                                        val pts = framesQueued * 1_000_000L / 44100L
                                        framesQueued += aligned / 4
                                        left -= aligned
                                        encoder.queueInputBuffer(inputIndex, 0, aligned, pts, 0)
                                    }
                                }
                            } else {
                                encoder.queueInputBuffer(inputIndex, 0, 0, framesQueued * 1_000_000L / 44100L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputFinished = true
                            }
                        }
                    }
                    val outputIndex = encoder.dequeueOutputBuffer(info, 10000)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val audioFormat = encoder.outputFormat
                            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                            trackIndex = muxer!!.addTrack(audioFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                        outputIndex >= 0 -> {
                            val encoded = encoder.getOutputBuffer(outputIndex)!!
                            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                check(muxerStarted) { "AAC encoder did not provide an output format" }
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                muxer!!.writeSampleData(trackIndex, encoded, info)
                            }
                            outputFinished = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
                check(muxerStarted) { "Could not initialize AAC output" }
            } finally {
                try { encoder.stop() } catch (_: Exception) {}
                encoder.release()
                try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
                muxer?.release()
            }
        }
        require(output.isFile && output.length() > 0L) { "AAC audio encoding failed" }
    }

    private fun combineTracks(video: File, audio: File, output: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoMap = IntArray(videoExtractor.trackCount) { -1 }
            val audioMap = IntArray(audioExtractor.trackCount) { -1 }
            var videoTracks = 0
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    if (videoTracks == 0 && format.containsKey("rotation-degrees")) {
                        val rotation = format.getInteger("rotation-degrees")
                        if (rotation in listOf(0, 90, 180, 270)) muxer.setOrientationHint(rotation)
                    }
                    videoTracks++
                }
                videoMap[i] = muxer.addTrack(format)
            }
            require(videoTracks > 0) { "Original MP4 has no video track" }
            var audioTracks = 0
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioMap[i] = muxer.addTrack(format)
                    audioTracks++
                }
            }
            require(audioTracks > 0) { "Encoded playback audio has no audio track" }
            muxer.start()
            started = true
            copySamples(videoExtractor, muxer, videoMap)
            copySamples(audioExtractor, muxer, audioMap)
        } finally {
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun copySamples(extractor: MediaExtractor, muxer: MediaMuxer, trackMap: IntArray) {
        for (i in trackMap.indices) if (trackMap[i] >= 0) extractor.selectTrack(i)
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val sourceTrack = extractor.sampleTrackIndex
            if (sourceTrack < 0) break
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
            muxer.writeSampleData(trackMap[sourceTrack], buffer, info)
            extractor.advance()
        }
    }

    private inline fun <T> MediaExtractor.useExtractor(block: (MediaExtractor) -> T): T {
        return try { block(this) } finally { release() }
    }
}
