# AGENTS.md — DeepseekVideoOven

## Project summary

CLI tool that takes any video, transcribes speech (whisper.cpp via FFM API), translates to Chinese (DeepSeek API), and burns hard subtitles (FFmpeg). Targets GraalVM Native Image for single-binary distribution.

## Build commands

```bash
# Fast compile (Java only, skip native C build)
mvn -Pdev compile

# Full build with native C libs (normal JVM JAR)
mvn package

# Native Image binary (requires GraalVM JDK)
mvn -Pnative package
# output: target/video-oven
```

Use `mvn -Pdev ...` or `-Dskip.native.build=true` for Java-only changes. Building the native C libs requires `cmake`, `gcc` installed.

**Important:** `mvn clean` deletes `target/`, runs `cmake --build whisper.cpp/build --target clean` (keeping cmake cache), and deletes `src/main/resources/native/*.{so,dylib,dll}`. Do NOT run `mvn clean -Pdev` — it wipes the native libs without rebuilding them. After a full `mvn package`, subsequent `mvn -Pdev compile` reuses the existing libs in `src/main/resources/native/`.

## Architecture

### Native layer (C → FFM API bridging)

```
native/whisper_bridge.c  →  libwhisper (submodule)        output → src/main/resources/native/
```

- `whisper.cpp/` is a **git submodule** — clone with `--recurse-submodules`
- C bridges are compiled during `generate-sources` phase directly into `src/main/resources/native/` so they're bundled in the JAR
- Platform detection (`so`/`dylib`/`dll`) is done inline by the gcc shell command via `uname -s`
- The C wrapper exists because `whisper_full_params` is a large C struct passed by value — we delegate the complex struct handling to C instead of defining fragile `MemoryLayout` in Java
- **JAR bundling**: both `libwhisper.*` and `libwhisper_bridge.*` land in JAR as `/native/libwhisper.*` etc.
- **Runtime loading**: `WhisperLib.java` uses `System.mapLibraryName()` to compute extensions, extracts from classpath (`/native/`) to a temp dir, loads `libwhisper` first then the bridge
- **Fat JAR**: maven-shade-plugin bundles Jackson and native libs into a single JAR

### FFmpeg (ProcessBuilder)

All FFmpeg operations use the `ffmpeg` CLI via `ProcessBuilder` — no C bridge or FFM bindings needed for FFmpeg.
- **Audio extraction**: `ffmpeg -i in.mp4 -vn -f f32le -ar 16000 -ac 1 pipe:` → raw float32 samples on stdout
- **Subtitle burning**: `ffmpeg -i in.mp4 -vf subtitles=subs.ass -c:v libx264 -c:a copy out.mp4 -y`

### Java FFM bindings (java.lang.foreign)

**FFmpegBridge.java** — static-only utility. Calls `ffmpeg` CLI via `ProcessBuilder`, reads stdout for audio samples. No FFM/JNI dependency.

**WhisperLib.java** — instance-based (holds whisper_context pointer). `static {}` loads `libwhisper_bridge.dylib` and creates all `static final MethodHandle`s. The `load(Path modelPath)` factory creates an instance by calling `whisper_init_from_file`. `close()` calls `whisper_free`. Uses `Arena.ofConfined()` for native memory.

### Pipeline (5 steps)

```
App.main() → PipelineOrchestrator.process()
  1. AudioExtractor    → FFmpegBridge.extractAudio()      float[] 16kHz mono
  2. SpeechRecognizer  → WhisperLib.transcribe()           List<SubtitleSegment>
  3. Translator        → DeepSeekClient.chat()             List<SubtitleSegment> (zh)
  4. SubtitleGenerator → pure Java ASS writer              subtitle.ass in temp dir
  5. VideoBurner       → FFmpegBridge.burnSubtitles()      output.mp4
```

### DeepSeek API client

- Endpoint: `POST https://api.deepseek.com/chat/completions` (no `/v1` prefix)
- Default model: `deepseek-v4-pro` (replaces deprecated `deepseek-chat`)
- Uses JDK `java.net.http.HttpClient`, Jackson for JSON serialization
- Batch translates 20 segments per API call to reduce round trips

## Key conventions

- **No runtime deps except Jackson** — CLI parsing is manual, HTTP is JDK HttpClient, FFmpeg is CLI (ProcessBuilder), whisper is FFM API
- **MethodHandle or VarHandler are `static final`** and created in `static {}` blocks — never created per-call
- **All FFM calls use `invokeExact`** — avoids boxing overhead
- **Java records** used for DTOs and models throughout
- **No external CLI framework** — argument parsing in `App.parseArgs()` is a simple switch-case

## Prerequisites for running

1. `ffmpeg` installed (`brew install ffmpeg` on macOS, `apt install ffmpeg` on Linux, or https://ffmpeg.org)
2. Whisper model downloaded to `~/.video-oven/models/ggml-*.bin`
3. DeepSeek API key in `~/.video-oven/config.json`:
   ```json
   { "deepseekApiKey": "sk-xxx" }
   ```
4. Native whisper library built: `mvn package` (or `-Pdev` if lib already exists in `target/native-libs/`)

## Native Image notes

- Profile `native` activates `graalvm.buildtools:native-maven-plugin`
- Requires `-H:+ForeignAPISupport` and `--enable-native-access=ALL-UNNAMED`
- Reflection config at `src/main/resources/META-INF/native-image/reflect-config.json` covers Jackson DTOs
