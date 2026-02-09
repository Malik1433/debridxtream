---
name: pro-playback-engine
description: Expert logic for ExoPlayer/Media3 implementation, handling 4K buffering, tunneling, and codec selection.
version: 2.0
---

# Professional Playback Standards

## 1. Buffer Configuration (LoadControl)
To achieve "instant" zapping like Tivimate:
* **Live TV:** Set `minBufferMs` to `2000` and `maxBufferMs` to `5000`. Use a custom `DefaultLoadControl`.
* **VOD/Movies:** Set `minBufferMs` to `15000` and `maxBufferMs` to `50000` for 4K stability.
* **Pre-caching:** Always implement a `CacheDataSourceFactory` (LRU Cache) effectively storing 200MB of recent stream data.

## 2. Android TV Specifics
* **Tunneling Mode:** ALWAYS enable `Tunneling` on Android TV devices (SDK > 21) to offload audio/video sync to hardware. This reduces dropped frames in 4K HDR content.
* **Refresh Rate:** Implement "Auto Frame Rate Match" to switch the TV from 60Hz to 24Hz/50Hz depending on the stream source.

## 3. Codec Handling
* Prefer `FFmpeg` extensions if hardware decoders fail.
* Logic: Try Hardware Decoder -> Fallback to Software Decoder -> Fallback to External Player (VLC intent).

## 4. Track Selection
* Automatically select Audio Language based on user locale (e.g., prefer 'Urdu' or 'Hindi' if available).
* Automatically enable Subtitles if audio language != user language.