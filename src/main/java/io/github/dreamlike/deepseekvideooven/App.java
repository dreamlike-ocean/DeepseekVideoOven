package io.github.dreamlike.deepseekvideooven;

import io.github.dreamlike.deepseekvideooven.asr.AsrEngine;
import io.github.dreamlike.deepseekvideooven.asr.WhisperAsrEngine;
import io.github.dreamlike.deepseekvideooven.config.ConfigLoader;
import io.github.dreamlike.deepseekvideooven.config.OvenConfig;
import io.github.dreamlike.deepseekvideooven.config.ToolDetector;
import io.github.dreamlike.deepseekvideooven.deepseek.DeepSeekClient;
import io.github.dreamlike.deepseekvideooven.ffmpeg.FFmpegBridge;
import io.github.dreamlike.deepseekvideooven.hunyuan.HunyuanTranslationClient;
import io.github.dreamlike.deepseekvideooven.pipeline.PipelineOrchestrator;
import io.github.dreamlike.deepseekvideooven.pipeline.SubtitleGenerator;
import io.github.dreamlike.deepseekvideooven.translation.TranslationClient;

import java.nio.file.Files;
import java.nio.file.Path;

public final class App {

    public static void main(String[] args) throws Exception {
        var cli = parseArgs(args);

        if (cli.help()) {
            printHelp();
            return;
        }

        if (cli.input() == null) {
            System.err.println("错误：必须提供 -i/--input <file>");
            printHelp();
            System.exit(1);
        }

        if (!Files.exists(cli.input())) {
            System.err.println("错误：输入文件不存在：" + cli.input());
            System.exit(1);
        }

        var pipelineMode = switch (cli.mode()) {
            case "burn" -> PipelineOrchestrator.Mode.BURN;
            case "soft" -> PipelineOrchestrator.Mode.SOFT;
            case "both" -> PipelineOrchestrator.Mode.BOTH;
            case "transcript" -> PipelineOrchestrator.Mode.TRANSCRIPT;
            case "asr" -> PipelineOrchestrator.Mode.ASR;
            default -> {
                System.err.println("错误：未知模式 '" + cli.mode() + "'，可选值为：burn、soft、both、transcript、asr");
                System.exit(1);
                yield PipelineOrchestrator.Mode.BURN;
            }
        };

        var config = mergeCli(ConfigLoader.load(cli.configPath()), cli);
        var resolved = ToolDetector.resolve(config, pipelineMode != PipelineOrchestrator.Mode.ASR);
        FFmpegBridge.configure(resolved.ffmpegPath());

        var subtitleFormat = switch (cli.subtitleFormat()) {
            case "ass" -> SubtitleGenerator.Format.ASS;
            case "srt" -> SubtitleGenerator.Format.SRT;
            default -> {
                System.err.println("错误：未知字幕格式 '" + cli.subtitleFormat() + "'，可选值为：ass、srt");
                System.exit(1);
                yield SubtitleGenerator.Format.ASS;
            }
        };

        var videoOutput = cli.output() != null
                ? cli.output()
                : replaceExtension(cli.input(), "_zh.mp4");

        var subtitleOutput = cli.output() != null
                ? replaceExtension(cli.output(), subtitleSuffix(subtitleFormat))
                : defaultSubtitleOutput(cli.input(), videoOutput, pipelineMode, subtitleFormat);

        try (var asr = createAsr(resolved);
             var client = pipelineMode == PipelineOrchestrator.Mode.ASR ? null : createTranslationClient(resolved)) {
            var pipeline = new PipelineOrchestrator(
                    asr,
                    client,
                    resolved.sourceLang(),
                    resolved.translation().extraPrompt(),
                    pipelineMode,
                    subtitleFormat
            );

            System.out.println("输入文件： " + cli.input());
            System.out.println("运行模式： " + cli.mode());
            var isVideoOut = pipelineMode == PipelineOrchestrator.Mode.BURN || pipelineMode == PipelineOrchestrator.Mode.BOTH;
            var isSubtitleOut = pipelineMode != PipelineOrchestrator.Mode.BURN;
            if (isVideoOut) {
                System.out.println("输出视频： " + videoOutput);
            }
            if (isSubtitleOut) {
                System.out.println("字幕格式： " + cli.subtitleFormat());
                System.out.println("字幕文件： " + subtitleOutput);
            }
            System.out.println("ASR 后端： " + resolved.asr().backend());
            System.out.println("翻译后端： " + resolved.translation().backend());
            System.out.println("源语言：   " + resolved.sourceLang());
            System.out.println("---");

            long totalStartedAt = System.nanoTime();
            pipeline.process(cli.input(), videoOutput, subtitleOutput);
            double totalSeconds = (System.nanoTime() - totalStartedAt) / 1_000_000_000.0;

            System.out.println("---");
            System.out.printf("总耗时： %.2f 秒%n", totalSeconds);
            if (isVideoOut) {
                System.out.println("完成： " + videoOutput.toAbsolutePath());
            }
            if (isSubtitleOut) {
                System.out.println("完成： " + subtitleOutput.toAbsolutePath());
            }
        }
    }

    record CliArgs(
            Path input,
            Path output,
            Path configPath,
            String lang,
            String model,
            String apiKey,
            String asrBackend,
            String translationBackend,
            String whisperModelPath,
            String hunyuanModelPath,
            Integer hunyuanGpuLayers,
            String mode,
            String subtitleFormat,
            boolean help
    ) {
    }

    private static CliArgs parseArgs(String[] args) {
        Path input = null;
        Path output = null;
        Path configPath = Path.of("config.json");
        String lang = null;
        String model = null;
        String apiKey = null;
        String asrBackend = null;
        String translationBackend = null;
        String whisperModelPath = null;
        String hunyuanModelPath = null;
        Integer hunyuanGpuLayers = null;
        String mode = "burn";
        String subtitleFormat = "ass";
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-i", "--input" -> input = Path.of(args[++i]);
                case "-o", "--output" -> output = Path.of(args[++i]);
                case "-c", "--config" -> configPath = Path.of(args[++i]);
                case "-l", "--lang" -> lang = args[++i];
                case "-m", "--model" -> model = args[++i];
                case "-k", "--api-key" -> apiKey = args[++i];
                case "--asr-backend" -> asrBackend = args[++i];
                case "--translation-backend" -> translationBackend = args[++i];
                case "--whisper-model" -> whisperModelPath = args[++i];
                case "--hunyuan-model" -> hunyuanModelPath = args[++i];
                case "--hunyuan-gpu-layers" -> hunyuanGpuLayers = Integer.parseInt(args[++i]);
                case "--mode" -> mode = args[++i];
                case "--subtitle-format" -> subtitleFormat = args[++i];
                case "-h", "--help" -> help = true;
                default -> {
                    System.err.println("未知参数：" + args[i]);
                    help = true;
                }
            }
        }
        return new CliArgs(
                input, output, configPath, lang, model, apiKey,
                asrBackend, translationBackend, whisperModelPath,
                hunyuanModelPath, hunyuanGpuLayers, mode, subtitleFormat, help
        );
    }

    private static OvenConfig mergeCli(OvenConfig config, CliArgs cli) {
        var asr = config.asr();
        var translation = config.translation();
        var whisper = asr.whisper();
        var hunyuan = translation.hunyuan();
        var deepseek = translation.deepseek();

        var translationBackend = cli.translationBackend != null ? cli.translationBackend : translation.backend();
        var deepseekModel = deepseek.model();
        var hunyuanModelPath = cli.hunyuanModelPath != null ? cli.hunyuanModelPath : hunyuan.modelPath();
        if (cli.model != null) {
            if ("hunyuan".equalsIgnoreCase(translationBackend)) {
                hunyuanModelPath = cli.model;
            } else {
                deepseekModel = cli.model;
            }
        }

        return new OvenConfig(
                config.ffmpegPath(),
                cli.lang != null ? cli.lang : config.sourceLang(),
                new OvenConfig.Asr(
                        cli.asrBackend != null ? cli.asrBackend : asr.backend(),
                        asr.initialPrompt(),
                        new OvenConfig.Whisper(cli.whisperModelPath != null ? cli.whisperModelPath : whisper.modelPath())
                ),
                new OvenConfig.Translation(
                        translationBackend,
                        translation.extraPrompt(),
                        new OvenConfig.Hunyuan(
                                hunyuanModelPath,
                                hunyuan.contextSize(),
                                cli.hunyuanGpuLayers != null ? cli.hunyuanGpuLayers : hunyuan.gpuLayers(),
                                hunyuan.threads(),
                                hunyuan.maxTokens(),
                                hunyuan.temperature(),
                                hunyuan.topP(),
                                hunyuan.topK(),
                                hunyuan.repeatPenalty()
                        ),
                        new OvenConfig.DeepSeek(cli.apiKey != null ? cli.apiKey : deepseek.apiKey(), deepseekModel)
                )
        );
    }

    private static AsrEngine createAsr(ToolDetector.ResolvedConfig config) {
        var asr = config.asr();
        return switch (asr.backend()) {
            case "whisper" -> WhisperAsrEngine.load(Path.of(asr.modelPath()), asr.initialPrompt());
            default -> throw new IllegalStateException("Unsupported ASR backend: " + asr.backend());
        };
    }

    private static TranslationClient createTranslationClient(ToolDetector.ResolvedConfig config) {
        var translation = config.translation();
        var hunyuan = translation.hunyuan();
        var deepseek = translation.deepseek();
        return switch (translation.backend()) {
            case "hunyuan" -> HunyuanTranslationClient.load(
                    Path.of(hunyuan.modelPath()),
                    hunyuan.contextSize(),
                    hunyuan.gpuLayers(),
                    hunyuan.threads(),
                    hunyuan.maxTokens(),
                    hunyuan.temperature(),
                    hunyuan.topP(),
                    hunyuan.topK(),
                    hunyuan.repeatPenalty()
            );
            case "deepseek" -> new DeepSeekClient(deepseek.apiKey(), deepseek.model());
            default -> throw new IllegalStateException("Unsupported translation backend: " + translation.backend());
        };
    }

    private static Path replaceExtension(Path path, String suffix) {
        var name = path.getFileName().toString();
        var dot = name.lastIndexOf('.');
        var base = dot > 0 ? name.substring(0, dot) : name;
        return path.resolveSibling(base + suffix);
    }

    private static Path defaultSubtitleOutput(
            Path input,
            Path videoOutput,
            PipelineOrchestrator.Mode mode,
            SubtitleGenerator.Format subtitleFormat
    ) {
        var suffix = subtitleSuffix(subtitleFormat);
        return switch (mode) {
            case SOFT, TRANSCRIPT -> replaceExtension(input, ".zh" + suffix);
            case ASR -> replaceExtension(input, ".asr" + suffix);
            case BURN, BOTH -> replaceExtension(videoOutput, suffix);
        };
    }

    private static String subtitleSuffix(SubtitleGenerator.Format subtitleFormat) {
        return switch (subtitleFormat) {
            case ASS -> ".ass";
            case SRT -> ".srt";
        };
    }

    private static void printHelp() {
        System.out.println("""
                DeepseekVideoOven —— 使用本地模型为任意视频生成中文字幕
                
                用法：video-oven -i <input> [options]
                
                参数：
                  -i, --input <file>          输入视频文件（必填）
                  -o, --output <file>         输出视频文件（默认：input_zh.mp4）
                  -c, --config <file>         配置文件路径（默认：./config.json）
                  -l, --lang <code>           源语言提示（en/ja/ko/auto）
                  -m, --model <path|name>     翻译模型；hunyuan 时是 GGUF 路径，deepseek 时是模型名
                  -k, --api-key <key>         DeepSeek API Key（仅 translation.backend=deepseek 时使用）
                  --asr-backend <backend>     whisper（默认）
                  --translation-backend <b>   hunyuan（默认） | deepseek
                  --whisper-model <file>      whisper.cpp ggml 模型
                  --hunyuan-model <file>      Hunyuan/Hy-MT GGUF 模型
                  --hunyuan-gpu-layers <n>    llama.cpp GPU offload 层数；0=CPU，999=尽量全量 offload
                  --mode <mode>               burn（默认） | soft | both | transcript | asr
                  --subtitle-format <f>       ass（默认） | srt
                  -h, --help                  显示帮助
                
                配置文件（./config.json）：
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
                
                说明：
                  - Hunyuan/Whisper 路径支持 macOS Metal / Linux CUDA。
                  - ffmpeg 当前仍用于音频解码；soft/transcript 模式不会烧录视频。
                """);
    }
}
