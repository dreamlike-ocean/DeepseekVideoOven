# DeepseekVideoOven

> 由 [opencode](https://github.com/anomalyco/opencode) + DeepSeek V4 Pro vibecoding 驱动生成的全自动烤肉机器

```bash
java -jar DeepseekVideoOven-1.0-SNAPSHOT.jar -i video.mp4
# 输出: video_zh.mp4（硬字幕）
```

## 工作流程

```
视频文件 → 提取音频(16kHz mono) → whisper.cpp 语音识别 → DeepSeek 翻译 → 生成 ASS 字幕 → ffmpeg 烧录
```

1. **音频提取** — ffmpeg 提取音频并重采样为 16kHz 单声道 float32 PCM
2. **语音识别** — whisper.cpp（通过 Java FFM API 调用）将音频转文字
3. **翻译** — DeepSeek API 将原文翻译为中文，每批 20 条
4. **字幕生成** — 生成 ASS 字幕文件
5. **硬字幕烧录** — ffmpeg 将字幕逐帧叠加到视频上，H.264 重编码

## 准备

1. **ffmpeg** — 提取音频和烧录字幕
   - macOS: `brew install ffmpeg`
   - Linux: `apt install ffmpeg`

2. **whisper 模型** — 下载到当前目录或 `models/` 下
   ```
   curl -L https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin -o models/ggml-small.bin
   ```
   | 模型 | 大小 | 说明 |
   |---|---|---|
   | ggml-tiny.bin | ~75 MB | 最快 |
   | ggml-small.bin | ~466 MB | **推荐** |
   | ggml-medium.bin | ~1.5 GB | 更准确 |
   | ggml-large-v3.bin | ~3 GB | 最准确 |

3. **DeepSeek API Key** — 在 `config.json` 中配置
   ```json
   {
     "ffmpegPath": "",
     "whisperModelPath": "models/ggml-small.bin",
     "deepseekApiKey": "sk-xxx",
     "deepseekModel": "deepseek-v4-pro",
     "defaultSourceLang": "auto"
   }
   ```
   | 字段 | 说明 | 默认值 |
   |---|---|---|
   | ffmpegPath | ffmpeg 路径 | 自动检测 |
   | whisperModelPath | 模型文件路径 | 自动搜索 ./models/ |
   | deepseekApiKey | API Key | - |
   | deepseekModel | 模型名 | deepseek-v4-pro |
   | defaultSourceLang | 源语言（en/ja/ko/auto） | auto |

## 编译

```bash
# 克隆（含 submodule）
git clone --recurse-submodules https://github.com/xxx/DeepseekVideoOven.git

# 完整构建（含 C 库编译）→ fat JAR
mvn package
# → target/DeepseekVideoOven-1.0-SNAPSHOT.jar

# 仅改 Java 代码，跳过 C 编译
mvn -Pdev compile

# Native Image 编译（需要 GraalVM JDK 25+）→ 独立二进制
mvn -Pnative package
# → target/video-oven（单文件可执行，启动更快，无 JDK 依赖）
```

- **JAR 模式**：需要 JDK 25+、cmake、gcc、NVIDIA CUDA Toolkit
- **Native Image 模式**：额外需要 GraalVM 25+（`$JAVA_HOME` 指向 GraalVM），产物是独立二进制，无 JRE 依赖

## 使用

```bash
# JAR 模式
java -jar DeepseekVideoOven-1.0-SNAPSHOT.jar -i video.mp4

# Native Image 模式（更快启动）
./video-oven -i video.mp4
```

| 参数 | 说明 | 默认值 |
|---|---|---|
| `-i, --input` | 输入视频文件（必填） | - |
| `-o, --output` | 输出视频文件 | input_zh.mp4 |
| `-c, --config` | 配置文件路径 | ./config.json |
| `-l, --lang` | 源语言提示 | auto |
| `-m, --model` | DeepSeek 模型 | deepseek-v4-pro |
| `-k, --api-key` | DeepSeek API Key | 从 config 读取 |
| `-h, --help` | 帮助 | - |

## 技术架构

```
App.main() → PipelineOrchestrator
  1. AudioExtractor   → ffmpeg CLI (ProcessBuilder)     float[] 16kHz mono
  2. SpeechRecognizer → libwhisper (FFM API)            原文字幕段落
  3. Translator       → DeepSeek API (HttpClient)       中文译文
  4. SubtitleGenerator → 纯 Java ASS 写入               subtitle.ass
  5. VideoBurner      → ffmpeg CLI (ProcessBuilder)     output.mp4
           ↑                     ↑
    FFmpegBridge.extractAudio   FFmpegBridge.burnSubtitles
```

- **无外部 CLI 框架** — 参数解析是手工 switch-case
- **运行时零依赖（除 Jackson）** — HTTP 用 JDK HttpClient，ffmpeg 调 CLI，whisper 用 FFM API
- **Fat JAR** — maven-shade-plugin 把 Jackson 和 native .so 全打进一个 JAR
- **GPU 加速** — whisper.cpp 编译了 CUDA 后端，有 GPU 自动用 GPU，无 GPU 回退 CPU
- **native 层** — `whisper_bridge.c` 薄封装 C struct 传递，避免 Java 侧定义复杂的 MemoryLayout
