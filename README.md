# ClipForge

Android screen recorder and short-form clip editor foundation, replacing the former WaveSync earbuds-controller concept.

## Product direction

ClipForge is designed for recording streams and gameplay, generating English subtitles, and turning recordings into edited clips.

### Planned workflow
1. Record the screen at 720p/1080p and 30/60 FPS when the device supports the selected encoder configuration.
2. Capture microphone audio optionally. Internal playback audio is a separate capability and may be blocked by the source app.
3. Generate live English captions while recording.
4. Run a post-recording transcription correction pass for better words and timestamps.
5. Edit subtitle text, timing, font, size, color, outline, position and animation.
6. Trim/split video, add outro cards and overlays, and export MP4 plus SRT.

## Current implementation
- Android MediaProjection consent flow.
- Foreground media-projection recording service.
- H.264 MP4 screen capture with selectable 720p/1080p and 30/60 FPS targets.
- Microphone capture is currently disabled in the app UI until its runtime-permission and audio pipeline are completed.
- Recording notification with Stop action.
- Files saved under the app-specific Movies/ClipForge directory.
- Saved MP4s can be opened in an installed video player through a temporary read-only FileProvider URI.
- Initial UI for the transcription and editor workflow.

## Roadmap
- [ ] Add encoder capability detection and fallback when a requested resolution or frame rate is unsupported.
- [ ] Export finished videos to the public media library.
- [ ] Enable microphone capture with an explicit runtime permission flow.
- [ ] Internal playback audio capture using Android AudioPlaybackCapture where permitted.
- [ ] Live English speech recognition with timestamped caption segments.
- [ ] Offline post-recording transcription and correction pass.
- [ ] Subtitle editor with SRT import/export and styling.
- [ ] Timeline editor: trim, split, overlays, outro and audio mix.
- [ ] Export presets and device capability detection.

## Compatibility and privacy
MediaProjection requires explicit Android system consent for each capture session. Some apps prevent playback-audio capture. Actual frame rate and resolution depend on device display and hardware encoder support. The initial recorder stores videos in app-specific external storage and does not upload recordings.
