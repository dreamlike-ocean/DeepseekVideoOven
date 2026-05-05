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

Use `mvn -Pdev ...` or `-Dskip.native.build=true` for Java-only changes. Building the native C libs requires `cmake`, `gcc`, `pkg-config`, `ffmpeg` headers installed.

**Important:** `mvn clean` deletes `target/` **and** `whisper.cpp/build/` (cmake cache + compiled libs). Do NOT run `mvn clean -Pdev` — it wipes the cached native libs without rebuilding them. After a full `mvn package`, subsequent `mvn -Pdev compile` reuses the existing dylibs in `target/native-libs/`.

## Architecture

### Native layer (C → FFM API bridging)

```
native/ffmpeg_bridge.c   →  libav*                        compiled by exec-maven-plugin
native/whisper_bridge.c  →  libwhisper (submodule)        output → target/native-libs/
```

- `whisper.cpp/` is a **git submodule** — clone with `--recurse-submodules`
- C bridges are compiled during `generate-sources` phase and copied to `target/native-libs/` by antrun
- The C wrappers exist because `whisper_full_params` is a large C struct passed by value — we delegate the complex struct handling to C instead of defining fragile `MemoryLayout` in Java

### Java FFM bindings (java.lang.foreign)

**FFmpegBridge.java** — static-only utility. `static {}` loads `libffmpeg_bridge.dylib`, creates `static final MethodHandle` fields via `Linker.downcallHandle()`. All calls use `invokeExact`.

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

- **No runtime deps except Jackson** — CLI parsing is manual, HTTP is JDK HttpClient, FFmpeg/whisper is FFM API
- **MethodHandles are `static final`** and created in `static {}` blocks — never created per-call
- **All FFM calls use `invokeExact`** — avoids boxing overhead
- **Java records** used for DTOs and models throughout
- **No external CLI framework** — argument parsing in `App.parseArgs()` is a simple switch-case

## Prerequisites for running

1. `ffmpeg` installed (`brew install ffmpeg`)
2. Whisper model downloaded to `~/.video-oven/models/ggml-*.bin`
3. DeepSeek API key in `~/.video-oven/config.json`:
   ```json
   { "deepseekApiKey": "sk-xxx" }
   ```
4. Native libraries built: `mvn package` (or `-Pdev` if libs already exist in `target/native-libs/`)

## Native Image notes

- Profile `native` activates `graalvm.buildtools:native-maven-plugin`
- Requires `-H:+ForeignAPISupport` and `--enable-native-access=ALL-UNNAMED`
- Reflection config at `src/main/resources/META-INF/native-image/reflect-config.json` covers Jackson DTOs
