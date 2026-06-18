# AGENTS.md — DeepseekVideoOven

## 项目摘要

DeepseekVideoOven 是一个本地优先的视频字幕 CLI：

1. 用 ffmpeg 从视频中提取 `16kHz mono float32` 音频。
2. 用 `whisper.cpp` 转录。
3. 用翻译后端翻译成中文：默认 Hunyuan/Hy-MT GGUF，经 `llama.cpp` C API 内嵌调用；可选 DeepSeek API。
4. 纯 Java 生成 ASS/SRT。
5. 可选 ffmpeg 硬字幕烧录。

目标仍然是 JVM JAR 和 GraalVM Native Image 单文件分发。运行期 Java 依赖尽量保持只有 Jackson，native 模型依赖走 git
submodule + Java FFM，不引入 Maven llama Java 封装。

## 构建命令

```bash
# Java 快速编译，跳过 native C/C++ 构建
mvn -Pdev compile

# 完整 JVM JAR，构建 whisper.cpp、llama.cpp 和 bridge
mvn package

# native backend 策略
#   -Dvideo.oven.cuda=AUTO|ON|OFF   AUTO 只在 Linux 且找到 CUDA Toolkit + nvcc 时启用 CUDA
#   -Dvideo.oven.metal=AUTO|ON|OFF  AUTO 在 macOS 启用 Metal

# Native Image，要求 GraalVM JDK
mvn -Pnative package
# output: target/video-oven
```

`mvn -Pdev ...` 或 `-Dskip.native.build=true` 用于 Java-only 改动。完整 native 构建需要 `cmake`、C/C++ 编译器。

**重要：** `mvn clean` 会删除 `target/` 并清理 `src/main/resources/native/` 中打包的 native 资源。`mvn clean -Pdev` 会跳过
native 资源清理。

支持平台是 Linux 和 macOS。Windows 不作为正确性目标。

## Native 层

```text
native/CMakeLists.txt
  -> third_party/whisper.cpp + whisper_bridge.c

native/hunyuan/CMakeLists.txt
  -> third_party/llama.cpp + hunyuan_bridge.cpp
```

- `third_party/whisper.cpp/`、`third_party/llama.cpp/` 都是 git submodule。
- whisper.cpp 和 llama.cpp 分两个 CMake 子构建，避免两个仓库携带的 ggml target 在同一个 CMake target 图中互相污染。
- CMake bundle target 会复制动态库和 `native/libs.txt` 到 `src/main/resources/native/`。
- `NativeLibraries.java` 统一负责从 classpath 解压 native 库并按依赖顺序 `System.load`。
- `WhisperLib`、`HunyuanLib` 只创建 `static final MethodHandle`，FFM 调用统一使用 `invokeExact`。

## FFmpeg 边界

FFmpeg 仍通过 `ProcessBuilder` 调 CLI：

- 音频提取：`ffmpeg -i in.mp4 -vn -f f32le -ar 16000 -ac 1 pipe:`
- 硬字幕：`ffmpeg -i in.mp4 -vf subtitles=subs.ass -c:v libx264 -c:a copy out.mp4 -y`

`--mode soft` / `--mode transcript` 不做硬烧录，但仍需要 ffmpeg 做音频解码。彻底移除 ffmpeg 需要另做 native 音频解码入口。

## Pipeline

```text
App.main() -> PipelineOrchestrator.process()
  1. AudioExtractor    -> FFmpegBridge.extractAudio()      float[] 16kHz mono
  2. SpeechRecognizer  -> AsrEngine                        List<SubtitleSegment>
  3. Translator        -> TranslationClient                 List<SubtitleSegment> (zh)
  4. SubtitleGenerator -> pure Java ASS/SRT writer
  5. VideoBurner       -> optional FFmpegBridge.burnSubtitles()
```

ASR 后端：

- `WhisperAsrEngine`：`WhisperLib.transcribe(float[])`，返回带时间戳分段。

翻译后端：

- `HunyuanTranslationClient`：本地 GGUF，经 `llama.cpp` C API 和 `hunyuan_bridge.cpp` 内嵌推理。不要走 `llama-server`。
- `DeepSeekClient`：仅在 `translation.backend=deepseek` 时使用 HTTP API。

## 代码约定

- 必须遵守既有项目风格，保持简单直接。
- 不要引入 CLI 框架、HTTP 框架、Maven llama Java 封装。
- native 大结构体留在 C/C++ bridge 内，Java FFM 只看稳定的小 C ABI。
- MethodHandle 必须是 `static final`，不要在热路径里动态创建。
- 默认翻译 prompt 保持泛化；样例专用术语放在用户配置里的 `translation.extraPrompt`。
- 编写 Java 代码时优先使用 IDE/MCP 符号工具查调用关系；必要时再用 `rg`/shell。
- 第三方 Java 库语义需要检查时，优先看本地 Maven 源码包而不是反编译。

## Native Image 注意事项

- `native` profile 使用 `graalvm.buildtools:native-maven-plugin`。
- 需要 `--enable-native-access=ALL-UNNAMED`。
- FFM downcall 地址只能在运行时解析，不要把 `WhisperLib`、`HunyuanLib` 做 build-time initialization。
