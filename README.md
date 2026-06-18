# DeepseekVideoOven

本地优先的视频字幕工具：提取视频音频，转录语音，翻译成中文，输出 ASS/SRT 或可选硬字幕视频。

```bash
java -jar target/DeepseekVideoOven-1.0-SNAPSHOT.jar -i video.mp4 --mode soft
```

## 支持平台

- **macOS**：支持。whisper.cpp/llama.cpp native 构建默认启用 Metal。
- **Linux**：支持。检测到 CUDA Toolkit + `nvcc` 时，whisper.cpp/llama.cpp 默认启用 CUDA。
- **Windows**：不作为保证目标。

## 工作流程

```text
视频文件
  -> ffmpeg 提取 16kHz mono float32 PCM
  -> ASR 后端：whisper.cpp
  -> 翻译后端：Hunyuan/Hy-MT GGUF 或 DeepSeek API
  -> 纯 Java 生成 ASS/SRT
  -> 可选 ffmpeg 硬字幕烧录
```

当前默认配置：

- ASR 默认 `whisper`，因为 whisper.cpp 已有 macOS Metal / Linux CUDA 路径。
- 翻译默认 `hunyuan`，通过 `llama.cpp` C API 内嵌调用，不启动 `llama-server`。
- `ffmpeg` 仍用于音频解码。`--mode soft` / `--mode transcript` 不烧录视频，但仍需要 ffmpeg 提取音频。

## 准备

1. 安装 `ffmpeg`

```bash
brew install ffmpeg          # macOS
sudo apt install ffmpeg      # Debian/Ubuntu
```

2. 初始化子模块

```bash
git submodule update --init --recursive
```

3. 准备模型

- Whisper: `~/.video-oven/models/ggml-small.bin`
- Hunyuan/Hy-MT: `~/.video-oven/models/Hy-MT2-1.8B-Q4_K_M.gguf`

## 配置

```json
{
  "ffmpegPath": "",
  "sourceLang": "auto",
  "asr": {
    "backend": "whisper",
    "initialPrompt": "Preserve spelling: PostgreSQL, Redis, CUDA, FFmpeg.",
    "whisper": { "modelPath": "~/.video-oven/models/ggml-small.bin" }
  },
  "translation": {
    "backend": "hunyuan",
    "extraPrompt": "术语约定：如果出现 EmployeeId 保留原文。",
    "hunyuan": { "modelPath": "~/.video-oven/models/Hy-MT2-1.8B-Q4_K_M.gguf", "gpuLayers": 999 },
    "deepseek": { "apiKey": "${DEEPSEEK_API_KEY}", "model": "deepseek-v4-pro" }
  }
}
```

## 编译

```bash
# Java 快速编译，不编 native
mvn -Pdev compile

# 完整 JVM JAR，编译 whisper.cpp、llama.cpp、native bridge
mvn package

# GPU 策略
mvn -Dvideo.oven.metal=ON package   # macOS Metal
mvn -Dvideo.oven.cuda=ON package    # Linux CUDA，必须找到 CUDA Toolkit + nvcc

# Native Image，需要 GraalVM JDK 25+
mvn -Pnative package
```

`mvn package` 会先通过 `native/CMakeLists.txt` 构建 whisper，再通过 `native/hunyuan/CMakeLists.txt` 单独构建
llama.cpp/hunyuan bridge，避免 whisper.cpp 和 llama.cpp 各自携带的 ggml target 互相污染。

## 使用

```bash
# 默认：Whisper ASR + Hunyuan 本地翻译 + 硬字幕视频
java -jar target/DeepseekVideoOven-1.0-SNAPSHOT.jar -i video.mp4

# 只输出字幕文件，不硬烧录
java -jar target/DeepseekVideoOven-1.0-SNAPSHOT.jar -i video.mp4 --mode soft --subtitle-format srt

# 只跑 ASR，便于对比后端
java -jar target/DeepseekVideoOven-1.0-SNAPSHOT.jar -i video.mp4 --mode asr --subtitle-format srt

# 使用 DeepSeek API 翻译
java -jar target/DeepseekVideoOven-1.0-SNAPSHOT.jar -i video.mp4 --translation-backend deepseek -k "$DEEPSEEK_API_KEY"
```

## ASR 后端选择

- 默认推荐 `whisper`：whisper.cpp 在 macOS/Linux 上有 Metal/CUDA 路径，分段和专名稳定性最好。
- ASR 不做翻译。`sourceLang` / `-l` 表示音频语言，例如英文演讲应设 `en`；中文输出由
  `translation.backend=hunyuan|deepseek` 负责。

| 参数                    | 说明                                                             |
|-------------------------|------------------------------------------------------------------|
| `-i, --input`           | 输入视频文件                                                     |
| `-o, --output`          | 输出视频文件                                                     |
| `-c, --config`          | 配置文件路径，默认 `./config.json`                               |
| `-l, --lang`            | 源语言提示，默认 `auto`                                          |
| `-m, --model`           | Hunyuan GGUF 路径或 DeepSeek 模型名                              |
| `--asr-backend`         | `whisper`                                                        |
| `--translation-backend` | `hunyuan` 或 `deepseek`                                          |
| `--hunyuan-gpu-layers`  | llama.cpp GPU offload 层数；`0` 为 CPU，`999` 为尽量全量 offload |
| `--mode`                | `burn`、`soft`、`both`、`transcript`、`asr`                      |
| `--subtitle-format`     | `ass` 或 `srt`                                                   |

## 架构

```text
App.main()
  -> AudioExtractor       ffmpeg CLI -> float[] 16kHz mono
  -> SpeechRecognizer    AsrEngine(WhisperAsrEngine)
  -> Translator          TranslationClient(HunyuanTranslationClient/DeepSeekClient)
  -> SubtitleGenerator   纯 Java ASS/SRT writer
  -> VideoBurner         可选 ffmpeg hard burn
```

- Java 侧只依赖 Jackson；CLI 解析、HTTP、字幕写入都用 JDK 标准库。
- native 侧通过小 C ABI 暴露给 Java FFM，Java 不直接描述 llama.cpp 的大结构体。
- whisper.cpp、llama.cpp 都放在 `third_party/` 下作为 git submodule。
- Hunyuan/Hy-MT 走 `llama.cpp` C API 内嵌执行，macOS Metal 和 Linux CUDA 由 llama.cpp/ggml backend 提供。
