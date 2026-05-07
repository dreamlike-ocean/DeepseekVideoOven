package io.github.dreamlike.deepseekvideooven;

import io.github.dreamlike.deepseekvideooven.config.ConfigLoader;
import io.github.dreamlike.deepseekvideooven.config.OvenConfig;
import io.github.dreamlike.deepseekvideooven.config.ToolDetector;
import io.github.dreamlike.deepseekvideooven.deepseek.DeepSeekClient;
import io.github.dreamlike.deepseekvideooven.pipeline.PipelineOrchestrator;
import io.github.dreamlike.deepseekvideooven.pipeline.SubtitleGenerator;

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

        var config = ConfigLoader.load(cli.configPath());
        config = mergeCli(config, cli);

        if (config.deepseekApiKey() == null || config.deepseekApiKey().isBlank()) {
            System.err.println("错误：未配置 DeepSeek API Key。");
            System.err.println("  请在 ./config.json 中设置，或通过 DEEPSEEK_API_KEY 环境变量传入。");
            System.exit(1);
        }

        var resolved = ToolDetector.resolve(config);

        var pipelineMode = switch (cli.mode()) {
            case "burn" -> PipelineOrchestrator.Mode.BURN;
            case "soft" -> PipelineOrchestrator.Mode.SOFT;
            case "both" -> PipelineOrchestrator.Mode.BOTH;
            case "transcript" -> PipelineOrchestrator.Mode.TRANSCRIPT;
            default -> {
                System.err.println("错误：未知模式 '" + cli.mode() + "'，可选值为：burn、soft、both、transcript");
                System.exit(1);
                yield PipelineOrchestrator.Mode.BURN;
            }
        };

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

        var modelPath = Path.of(resolved.whisperModelPath());
        var client = new DeepSeekClient(resolved.deepseekApiKey(), resolved.deepseekModel());
        var pipeline = new PipelineOrchestrator(
                client,
                modelPath,
                resolved.defaultSourceLang(),
                resolved.extraTranslationPrompt(),
                pipelineMode,
                subtitleFormat
        );

        System.out.println("输入文件： " + cli.input());
        System.out.println("运行模式： " + cli.mode());
        var isVideoOut = pipelineMode == PipelineOrchestrator.Mode.BURN || pipelineMode == PipelineOrchestrator.Mode.BOTH;
        var isAssOut = pipelineMode != PipelineOrchestrator.Mode.BURN;
        if (isVideoOut) {
            System.out.println("输出视频： " + videoOutput);
        }
        if (isAssOut) {
            System.out.println("字幕格式： " + cli.subtitleFormat());
            System.out.println("字幕文件： " + subtitleOutput);
        }
        System.out.println("翻译模型： " + resolved.deepseekModel());
        System.out.println("源语言：   " + resolved.defaultSourceLang());
        System.out.println("---");

        long totalStartedAt = System.nanoTime();
        pipeline.process(cli.input(), videoOutput, subtitleOutput);
        double totalSeconds = (System.nanoTime() - totalStartedAt) / 1_000_000_000.0;

        System.out.println("---");
        System.out.printf("总耗时： %.2f 秒%n", totalSeconds);
        if (isVideoOut) {
            System.out.println("完成： " + videoOutput.toAbsolutePath());
        }
        if (isAssOut) {
            System.out.println("完成： " + subtitleOutput.toAbsolutePath());
        }
    }

    record CliArgs(
            Path input,
            Path output,
            Path configPath,
            String lang,
            String model,
            String apiKey,
            String mode,
            String subtitleFormat,
            boolean help
    ) {}

    private static CliArgs parseArgs(String[] args) {
        Path input = null;
        Path output = null;
        Path configPath = Path.of("config.json");
        String lang = null;
        String model = null;
        String apiKey = null;
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
                case "--mode" -> mode = args[++i];
                case "--subtitle-format" -> subtitleFormat = args[++i];
                case "-h", "--help" -> help = true;
                default -> {
                    System.err.println("未知参数：" + args[i]);
                    help = true;
                }
            }
        }
        return new CliArgs(input, output, configPath, lang, model, apiKey, mode, subtitleFormat, help);
    }

    private static OvenConfig mergeCli(OvenConfig config, CliArgs cli) {
        return new OvenConfig(
                config.ffmpegPath(),
                config.whisperModelPath(),
                cli.apiKey != null ? cli.apiKey : config.deepseekApiKey(),
                cli.model != null ? cli.model : config.deepseekModel(),
                cli.lang != null ? cli.lang : config.defaultSourceLang(),
                config.extraTranslationPrompt()
        );
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
                DeepseekVideoOven —— 使用 DeepSeek API 为任意视频生成中文字幕

                用法：video-oven -i <input> [options]

                参数：
                  -i, --input <file>     输入视频文件（必填）
                  -o, --output <file>    输出视频文件（默认：input_zh.mp4）
                  -c, --config <file>    配置文件路径（默认：./config.json）
                  -l, --lang <code>      源语言提示（en/ja/ko/auto）
                  -m, --model <name>     DeepSeek 模型（默认：deepseek-v4-pro）
                  -k, --api-key <key>    DeepSeek API Key（优先级高于配置文件）
                  --mode <mode>          burn（默认） | soft | both | transcript
                  --subtitle-format <f>  ass（默认） | srt
                                         burn = 输出硬字幕视频
                                         soft = 仅输出字幕文件
                                         both = 同时输出视频和字幕
                                         transcript = 输出字幕和 .txt 文稿
                  -h, --help             显示帮助

                配置文件（./config.json）：
                  {
                    "ffmpegPath": "",
                    "whisperModelPath": "~/.video-oven/models/ggml-small.bin",
                    "deepseekApiKey": "sk-xxx",
                    "deepseekModel": "deepseek-v4-pro",
                    "defaultSourceLang": "auto",
                    "extraTranslationPrompt": "术语约定：如果出现 EmployeeId 保留原文；歌名保留原文并在必要时补中文括注。"
                  }
                  （ffmpegPath 和 whisperModelPath 可省略，程序会自动探测）

                运行前准备：
                  - ffmpeg (https://ffmpeg.org/download.html)
                  - Whisper 模型：将 ggml-*.bin 放到 ./models/ 或 ~/.video-oven/models/
                    下载地址：https://huggingface.co/ggerganov/whisper.cpp/tree/main
                  - DeepSeek API Key：https://platform.deepseek.com/api_keys
                """);
    }
}
