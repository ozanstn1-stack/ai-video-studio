# AI Video Studio

An Android app that turns long action-camera footage into short-form vertical video.

Import your clips, the app analyses them on-device, decides which moments are worth
keeping, assembles them into coherent Shorts with a hook and a payoff, writes captions
and titles, and exports a file ready for TikTok, Reels or YouTube Shorts.

Built for DJI Osmo Action / Pocket footage, but it works with any MP4/MOV the platform
can decode.

---

## Status

Everything described below is implemented and builds. There is no mock AI: every
feature either does real work on the device or talks to a real, user-configured API.

| Area | What actually happens |
| --- | --- |
| Import | Android Photo Picker, or a whole folder through the Storage Access Framework (DJI folders are walked recursively, `_LRV` proxies are skipped) |
| Video probe | `MediaExtractor` + `MediaMetadataRetriever` for duration, resolution, fps, bitrate, codecs, rotation and recording date |
| Scene detection | Frames are sampled from sync points and reduced to a small luma grid; histogram shift + brightness jumps split the footage into scenes |
| Quality analysis | Per-scene brightness, focus (Laplacian energy), motion, shake, saturation, framing, plus hard-fail detection (lens covered, too dark, overexposed, blurred, static, duplicated, pointed at the ground) |
| Audio analysis | The audio track is decoded through `MediaCodec` and reduced to a 100 ms energy profile: noise floor, speech ranges, silence ranges, clipping, and a waveform envelope |
| Speech to text | On-device: Android's offline recogniser fed the extracted audio file (API 33+). Cloud: Whisper-style endpoint, or Gemini. Both fall back to speech *timing* without words, and say so |
| Highlight scoring | A weighted model over visual / audio / speech / motion / interest / story / uniqueness, with per-mode weights (Travel, Action, Vlog, Food, Cinematic, Sports…) |
| Short generation | Moments are assembled into Hook → Context → Climax → Payoff sequences with a time budget, never four random cuts |
| Smart cropping | ML Kit face detection decides where the subject is; the largest target-aspect window containing them is used, with manual override |
| Subtitles | Transcript is mapped onto the *rendered* timeline, split into readable chunks, with five styles (Clean, Bold, Minimal, Creator, Dynamic) |
| Titles & description | Provider-backed, with a deterministic local generator as the fallback so a clip is never left untitled |
| Editor | Trim, split, delete, reorder, speed, crop zoom/centre, caption text/style/position, original mute + volume, music bed, text overlays, aspect ratio |
| AI Improve | Computes concrete suggestions from the current edit state (opening too long, over-target length, static ending, subtitles too low, missing hook, hot audio, fragments too short) and applies them only when the user confirms |
| Export | Media3 `Transformer` composition: source ranges are never rewritten, captions and text are drawn by a per-frame canvas overlay, with bitrate/quality/aspect control and background execution through WorkManager |
| Projects | Room database; a killed process resumes the pipeline from the last unfinished stage |

## What genuinely needs an external service

Two things cannot be done fully offline on a phone today, and the app is honest about it:

1. **Word-accurate transcription on every device.** Android's offline speech recogniser
   is only available from API 33 and not on every OEM build. Without it, the app still
   detects *where* speech happens and builds Shorts from that, but the caption words are
   missing and the UI says so.
2. **Semantic understanding** (subjects, places, mood, storytelling) and **LLM-written
   titles/descriptions.** Without a configured provider, the app uses deterministic
   heuristics and templates instead. They work, they are just less colourful.

Both are solved by configuring a provider in **Settings → AI Provider**:

- **OpenAI-compatible** — OpenAI, Groq, OpenRouter, Azure gateways, LM Studio, Ollama's
  compatibility endpoint, or your own proxy. Base URL, model and API key.
- **Google Gemini** — `generateContent` with inline audio and images.

API keys are encrypted with an AES-256-GCM key held in the Android Keystore. They are
never logged, never written to backups, and never compiled into the APK
(`local.properties` / environment variables only).

## Privacy model

The default processing mode is **Always ask**. A cloud provider is only reachable when
the user picked one, configured credentials, chose a permissive mode, *and* consented.
Everything else falls back to the on-device provider so no feature simply breaks.

- Original camera files are only ever opened for reading. They are never modified,
  moved, renamed or deleted.
- Every clip is a list of `[start, end)` ranges pointing back at the originals.
- Deleting a project removes the app's own generated files and database rows, never
  source media.

## Architecture

```
app/src/main/java/com/aivideostudio/
├── core/            dispatchers, time/size formatting, constants, Keystore secret store, Hilt modules
├── domain/          pure models + repository interfaces (no Android, no Room)
├── data/            Room entities/DAOs, mappers, DataStore settings, repositories, app file paths
├── media/           probe, frame sampling, thumbnails, audio decoding, scene/face analysis, Media3 export
├── ai/              provider abstraction (local / OpenAI-compatible / Gemini), scoring, story building,
│                    subtitles, smart cropping, text generation, AI Improve engine
├── processing/      the resumable stage pipeline and the Short generator
├── work/            WorkManager workers + scheduler
└── ui/              Compose: theme, navigation, components, one package per screen
```

Layering is enforced by construction: `domain` has no Android imports, `ai` reasoning is
pure Kotlin so it can be unit tested without a device, and the pipeline reports progress
from a non-suspending callback so it never depends on the UI.

### The pipeline

```
IMPORT → PROBE_VIDEO → GENERATE_THUMBNAILS → SCENE_DETECTION → AUDIO_ANALYSIS
      → TRANSCRIPTION → SEMANTIC_ANALYSIS → HIGHLIGHT_DETECTION → SHORT_GENERATION → RENDER
```

One worker per stage. Each stage is idempotent, and completed stages are recorded on the
job row, so a process death during transcription resumes at transcription rather than
re-analysing an hour of footage.

### Long video handling

Sample counts are capped and the interval adapts to the duration; audio is reduced to a
profile as it streams and the PCM is discarded; audio for cloud transcription is a
*remuxed* compressed track (no re-encode), chunked into ten-minute pieces. Memory stays
flat regardless of source length.

## Tech

Kotlin · Jetpack Compose · Material 3 · Media3 (ExoPlayer, Transformer, Effect) · Room ·
WorkManager · Hilt · Coroutines/Flow · DataStore · Coil (+ video frames) · ML Kit face
detection · OkHttp · kotlinx.serialization

`minSdk 29` (Android 10) · `targetSdk 37` · AGP 9.1.1 · Gradle 9.3.1

## Building

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleRelease      # minified release APK
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:lintDebug
```

Create `local.properties` (git-ignored) with `sdk.dir=...`. Optionally pre-seed a
provider for a personal build:

```properties
AI_BASE_URL=https://api.openai.com/v1
AI_API_KEY=
AI_MODEL=gpt-4o-mini
```

These are read at build time into `BuildConfig` and are only used as *defaults* the first
time the app runs — they are never required, and leaving them empty is the normal case.

## Tests

62 unit tests, all passing, covering the parts where a wrong answer would be invisible
until the user watched the result:

- `HighlightScorerTest` — scene filtering, mode weighting, non-overlapping selection, hook/payoff signals
- `StoryBuilderTest` — story structure and ordering, source-range safety, degradation with thin footage
- `SubtitleBuilderTest` — source-to-output time mapping, cross-asset isolation, chunking, overlap prevention, speed changes, face avoidance
- `SmartCropperTest` — NDC geometry, clamping, rotation, face-driven offset, zoom, strategy selection
- `EditorSuggestionEngineTest` — every AI Improve heuristic, including when it must stay quiet
- `CoreTest` — formatting, JSON codec robustness, pipeline progress weights

## Known limitations

- Native FFmpeg is not bundled; export uses Media3's `Transformer`. This means only
  codecs the device's hardware encoder supports can be written, and unusual source
  containers are rejected with a clear message rather than a crash.
- Face detection runs every few seconds, not every frame; crop centres are per scene.
- On-device transcription needs Android 13+ and an OEM build that ships the offline
  recogniser.
- No music library is bundled — the editor accepts an audio file of the user's choosing.
