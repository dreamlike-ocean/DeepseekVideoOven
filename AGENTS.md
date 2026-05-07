# AGENTS.md — DeepseekVideoOven

## Project summary

CLI tool that takes any video, transcribes speech (whisper.cpp via FFM API), translates to Chinese (DeepSeek API), and burns hard subtitles (FFmpeg). Targets GraalVM Native Image for single-binary distribution.

## Build commands

```bash
# Fast compile (Java only, skip native C build)
mvn -Pdev compile

# Full build with native C libs (normal JVM JAR)
mvn package
# Optional native backend policy:
#   -Dvideo.oven.cuda=AUTO|ON|OFF   (AUTO enables CUDA only on Linux when CUDA Toolkit + nvcc are found)
#   -Dvideo.oven.metal=AUTO|ON|OFF  (AUTO enables Metal on macOS)

# Native Image binary (requires GraalVM JDK)
mvn -Pnative package
# output: target/video-oven
```

Use `mvn -Pdev ...` or `-Dskip.native.build=true` for Java-only changes. Building the native C libs requires `cmake` and a C/C++ compiler.

**Important:** `mvn clean` deletes `target/` and removes bundled native resources from `src/main/resources/native/`. `mvn clean -Pdev` skips native resource cleanup and only deletes `target/`. After a full `mvn package`, subsequent `mvn -Pdev compile` reuses the existing libs in `src/main/resources/native/`.

Supported platforms are Linux and macOS. Windows may work incidentally but is not a correctness target.

## Architecture

### Native layer (C → FFM API bridging)

```
native/CMakeLists.txt → whisper.cpp + whisper_bridge.c    output → src/main/resources/native/
```

- `whisper.cpp/` is a **git submodule** — clone with `--recurse-submodules`
- Native libraries are compiled during `generate-sources` through CMake into `target/native-build/`, then copied into `src/main/resources/native/` so they're bundled in the JAR
- Backend policy is controlled by `native/CMakeLists.txt`: CUDA `AUTO` enables `GGML_CUDA` only on Linux when CUDA Toolkit and `nvcc` are found; Metal `AUTO` enables `GGML_METAL` on macOS
- The C wrapper exists because `whisper_full_params` is a large C struct passed by value — we delegate the complex struct handling to C instead of defining fragile `MemoryLayout` in Java
- **JAR bundling**: the CMake `video_oven_native_bundle` target copies `libwhisper`, `libwhisper_bridge`, `libggml*`, and `native/libs.txt` into `src/main/resources/native/`
- **Runtime loading**: `WhisperLib.java` reads `native/libs.txt`, extracts all bundled native libs to a temp dir, then loads ggml base/backends, `libwhisper`, and the bridge in dependency order
- **Fat JAR**: maven-shade-plugin bundles Jackson and native libs into a single JAR

### FFmpeg (ProcessBuilder)

All FFmpeg operations use the `ffmpeg` CLI via `ProcessBuilder` — no C bridge or FFM bindings needed for FFmpeg.
- **Audio extraction**: `ffmpeg -i in.mp4 -vn -f f32le -ar 16000 -ac 1 pipe:` → raw float32 samples on stdout
- **Subtitle burning**: `ffmpeg -i in.mp4 -vf subtitles=subs.ass -c:v libx264 -c:a copy out.mp4 -y`

### Java FFM bindings (java.lang.foreign)

**FFmpegBridge.java** — static-only utility. Calls `ffmpeg` CLI via `ProcessBuilder`, reads stdout for audio samples. No FFM/JNI dependency.

**WhisperLib.java** — instance-based (holds whisper_context pointer). `static {}` loads the platform native libs and creates all `static final MethodHandle`s. The `load(Path modelPath)` factory creates an instance by calling `whisper_init_from_file`. `close()` calls `whisper_free`. Uses `Arena.ofConfined()` for native memory.

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
4. Native whisper library built: `mvn package` (or `-Pdev` if lib already exists in `src/main/resources/native/`)

## Native Image notes

- Profile `native` activates `graalvm.buildtools:native-maven-plugin`
- Requires `--enable-native-access=ALL-UNNAMED` (FFM restricted operations)
- `-H:+ForeignAPISupport` is default in GraalVM 25, kept for explicitness
- **Reachability metadata** at `src/main/resources/META-INF/native-image/reachability-metadata.json` covering:
  - Reflection config for Jackson DTOs (6 records)
  - Foreign downcalls config for 7 whisper functions (`whisper_init_from_file`, `whisper_bridge_transcribe`, `whisper_full_*`, `whisper_free`)
- Key constraint: `WhisperLib`'s `static {}` calls `System.load()` and creates `MethodHandle`s at runtime — build-time initialization of this class is NOT possible. The downcall handles contain native function addresses that differ between build VM and native image runtime.
