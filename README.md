# ClipForge

Android screen recorder and short-form clip editor foundation, replacing the former WaveSync earbuds-controller concept.

## Product direction

ClipForge is designed for recording streams and gameplay, generating English subtitles, and turning recordings into edited clips.

### Planned workflow
1. Record the screen at 720p/1080p and 30/60 FPS when the device supports the selected encoder configuration.
2. Capture microphone audio optionally, or enable Android 10+ internal playback capture when the source app permits it. Internal audio is currently saved as a separate WAV sidecar rather than muxed into the MP4.
3. Generate live English captions while recording.
4. Run a post-recording transcription correction pass for better words and timestamps.
5. Edit subtitle text, timing, font, size, color, outline, position and animation.
6. Trim/split video, add outro cards and overlays, and export MP4 plus SRT.

## Current implementation
- Android MediaProjection consent flow.
- Foreground media-projection recording service.
- H.264 MP4 screen capture with selectable 720p/1080p and 30/60 FPS targets.
- Optional microphone capture is available through an explicit Android runtime permission and user toggle.
- Optional Android 10+ playback capture uses AudioPlaybackCapture for media/game/unknown usages when the source app allows capture; it writes a separate 44.1 kHz stereo PCM WAV beside the MP4. The WAV can be opened from the recording row. It is not yet merged into the MP4.
- Recording notification with Stop action.
- Files saved under the app-specific Movies/ClipForge directory.
- Saved MP4s can be opened in an installed video player through a temporary read-only FileProvider URI.
- Recordings can be copied to the public `Movies/ClipForge` gallery folder through Android MediaStore.
- H.264 encoder capability checks select a supported resolution/frame-rate fallback when the requested combination is unavailable.
- Offline English speech recognition using the Vosk Android engine.
- Optional one-time download of the approximately 40 MB English model; transcription then runs locally.
- Post-recording audio extraction from MP4, timestamped word recognition, and SRT subtitle generation.
- SRT import for existing subtitle files and sharing/export of generated or edited SRT files.
- Basic SRT text/timestamp editor with cue-number, timestamp-format, and start/end validation before saving.
- Initial UI for the subtitle and editor workflow.

## Roadmap
- [x] Initial internal playback audio capture using Android AudioPlaybackCapture where permitted; save a separate WAV sidecar.
- [ ] Mux captured playback audio into the recorded MP4 and handle unsupported/blocked source apps gracefully.
- [ ] Live English speech recognition with timestamped caption segments.
- [ ] Improve transcription accuracy and add correction assistance.
- [ ] Visual subtitle styling and on-video preview.
- [ ] Timeline editor: trim, split, overlays, outro and audio mix.
- [ ] Export presets and device capability detection.

## Compatibility and privacy
MediaProjection requires explicit Android system consent for each capture session. Some apps prevent playback-audio capture. Actual frame rate and resolution depend on device display and hardware encoder support. The recorder stores originals in app-specific external storage; users can separately copy a recording to the public Movies/ClipForge media library. Recordings are not uploaded. The optional English model download is the only network step for the first offline transcription workflow; video and audio are processed locally.
