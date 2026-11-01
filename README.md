# ClipForge

Android screen recorder and short-form clip editor foundation, replacing the former WaveSync earbuds-controller concept.

## Product direction

ClipForge is designed for recording streams and gameplay, generating English subtitles, and turning recordings into edited clips.

### Planned workflow
1. Record the screen at 720p/1080p and 30/60 FPS when the device supports the selected encoder configuration.
2. Capture microphone audio optionally, or enable Android 10+ internal playback capture when the source app permits it. Internal audio is captured to a WAV sidecar and then encoded to AAC and muxed into the MP4 after recording when the device supports it; the WAV is retained as a fallback.
3. Import a video into ClipForge or select a recording from the in-app library.
4. Play videos in the built-in editor, select a trim range and export a separate MP4 clip.
5. Generate English SRT subtitles locally with the installed Vosk model, then correct and export them.
6. Create a 1280 × 720 thumbnail from a video frame with a custom title.
7. Share the MP4 through Android's upload/share chooser to YouTube or another supported app.
4. Run a post-recording transcription correction pass for better words and timestamps.
5. Edit subtitle text, timing, font, size, color, outline, position and animation.
6. Trim/split video, add outro cards and overlays, and export MP4 plus SRT.

## Current implementation
- Android MediaProjection consent flow.
- Foreground media-projection recording service.
- H.264 MP4 screen capture with selectable 720p/1080p and 30/60 FPS targets.
- Optional microphone capture is available through an explicit Android runtime permission and user toggle.
- Optional Android 10+ playback capture uses AudioPlaybackCapture for media/game/unknown usages when the source app allows capture; it writes a separate 44.1 kHz stereo PCM WAV beside the MP4. The WAV can be opened from the recording row. After recording, ClipForge converts the WAV to AAC and adds it as an MP4 audio track. If muxing fails, the original MP4 is preserved and the WAV remains available.
- Recording notification with Stop action.
- Files saved under the app-specific Movies/ClipForge directory.
- Built-in video playback and a basic editor screen; recordings no longer need to open in a separate player.
- Import MP4 or other supported video files into the ClipForge library.
- Basic lossless trim export using MediaExtractor/MediaMuxer; the selected start may move back to the nearest keyframe.
- Thumbnail generator creates a 1280 × 720 JPEG using a frame from the video and a user-supplied title.
- Android share/upload chooser for sending a video to YouTube or other installed destinations; this is not direct YouTube account/API upload.
- Recordings can be copied to the public `Movies/ClipForge` gallery folder through Android MediaStore.
- H.264 encoder capability checks select a supported resolution/frame-rate fallback when the requested combination is unavailable.
- Offline English speech recognition using the Vosk Android engine.
- Optional one-time download of the approximately 40 MB English model; transcription then runs locally.
- Post-recording transcription prefers the captured internal-playback WAV sidecar when present, converts 44.1 kHz stereo PCM to 16 kHz mono locally, and generates timestamped SRT subtitles adjusted by the recorded audio-start offset; otherwise it decodes an audio track from the MP4.
- SRT import for existing subtitle files and sharing/export of generated or edited SRT files.
- Basic SRT text/timestamp editor with cue-number, timestamp-format, and start/end validation before saving.
- Initial UI for the subtitle and editor workflow.

## Roadmap
- [x] Initial internal playback audio capture using Android AudioPlaybackCapture where permitted; save a separate WAV sidecar.
- [x] Encode captured playback WAV to AAC and mux it into the MP4 after recording, preserving the original if muxing fails.
- [ ] Improve audio/video start-time alignment and mixed microphone/internal audio output.
- [ ] Live English speech recognition with timestamped caption segments.
- [ ] Improve transcription accuracy and add correction assistance.
- [ ] Visual subtitle styling and on-video preview.
- [x] Built-in playback and basic trim export.
- [x] Import video, generate local English SRT subtitles, create a basic thumbnail and share/upload through Android chooser.
- [ ] Full multi-track timeline, frame-accurate re-encoded cuts, visual subtitle burn-in/styling, overlays, outro and audio mix.
- [ ] Export presets and device capability detection.

## Compatibility and privacy
MediaProjection requires explicit Android system consent for each capture session. Some apps prevent playback-audio capture. Actual frame rate and resolution depend on device display and hardware encoder support. The recorder stores originals in app-specific external storage; users can separately copy a recording to the public Movies/ClipForge media library. Recordings are not uploaded. The optional English model download is the only network step for the first offline transcription workflow; video and audio are processed locally.
