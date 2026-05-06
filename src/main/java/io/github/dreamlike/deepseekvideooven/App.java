package io.github.dreamlike.deepseekvideooven;

import io.github.dreamlike.deepseekvideooven.config.ConfigLoader;
import io.github.dreamlike.deepseekvideooven.config.OvenConfig;
import io.github.dreamlike.deepseekvideooven.config.ToolDetector;
import io.github.dreamlike.deepseekvideooven.deepseek.DeepSeekClient;
import io.github.dreamlike.deepseekvideooven.pipeline.PipelineOrchestrator;

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
            System.err.println("Error: -i/--input <file> is required");
            printHelp();
            System.exit(1);
        }

        if (!Files.exists(cli.input())) {
            System.err.println("Error: input file not found: " + cli.input());
            System.exit(1);
        }

        var config = ConfigLoader.load(cli.configPath());
        config = mergeCli(config, cli);

        if (config.deepseekApiKey() == null || config.deepseekApiKey().isBlank()) {
            System.err.println("Error: DeepSeek API key not configured.");
            System.err.println("  Set it in ./config.json or via DEEPSEEK_API_KEY env var.");
            System.exit(1);
        }

        var resolved = ToolDetector.resolve(config);

        var videoOutput = cli.output() != null
                ? cli.output()
                : replaceExtension(cli.input(), "_zh.mp4");

        var assOutput = replaceExtension(videoOutput, ".ass");

        var pipelineMode = switch (cli.mode()) {
            case "burn" -> PipelineOrchestrator.Mode.BURN;
            case "soft" -> PipelineOrchestrator.Mode.SOFT;
            case "both" -> PipelineOrchestrator.Mode.BOTH;
            case "transcript" -> PipelineOrchestrator.Mode.TRANSCRIPT;
            default -> {
                System.err.println("Error: unknown mode '" + cli.mode() + "', valid: burn, soft, both, transcript");
                System.exit(1);
                yield PipelineOrchestrator.Mode.BURN;
            }
        };

        var modelPath = Path.of(resolved.whisperModelPath());
        var client = new DeepSeekClient(resolved.deepseekApiKey(), resolved.deepseekModel());
        var pipeline = new PipelineOrchestrator(client, modelPath, resolved.defaultSourceLang(), pipelineMode);

        System.out.println("Input:  " + cli.input());
        System.out.println("Mode:   " + cli.mode());
        var isVideoOut = pipelineMode == PipelineOrchestrator.Mode.BURN || pipelineMode == PipelineOrchestrator.Mode.BOTH;
        var isAssOut = pipelineMode != PipelineOrchestrator.Mode.BURN;
        if (isVideoOut) {
            System.out.println("Video:  " + videoOutput);
        }
        if (isVideoOut) {
            System.out.println("Video:  " + videoOutput);
        }
        if (isAssOut) {
            System.out.println("Ass:    " + assOutput);
        }
        System.out.println("Model:  " + resolved.deepseekModel());
        System.out.println("Lang:   " + resolved.defaultSourceLang());
        System.out.println("---");

        pipeline.process(cli.input(), videoOutput, assOutput);

        System.out.println("---");
        if (isVideoOut) {
            System.out.println("Done: " + videoOutput.toAbsolutePath());
        }
        if (isAssOut) {
            System.out.println("Done: " + assOutput.toAbsolutePath());
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
                case "-h", "--help" -> help = true;
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    help = true;
                }
            }
        }
        return new CliArgs(input, output, configPath, lang, model, apiKey, mode, help);
    }

    private static OvenConfig mergeCli(OvenConfig config, CliArgs cli) {
        return new OvenConfig(
                config.ffmpegPath(),
                config.whisperModelPath(),
                cli.apiKey != null ? cli.apiKey : config.deepseekApiKey(),
                cli.model != null ? cli.model : config.deepseekModel(),
                cli.lang != null ? cli.lang : config.defaultSourceLang()
        );
    }

    private static Path replaceExtension(Path path, String suffix) {
        var name = path.getFileName().toString();
        var dot = name.lastIndexOf('.');
        var base = dot > 0 ? name.substring(0, dot) : name;
        return path.resolveSibling(base + suffix);
    }

    private static void printHelp() {
        System.out.println("""
                DeepseekVideoOven — Burn Chinese subtitles into any video using DeepSeek API
                                
                Usage: video-oven -i <input> [options]
                                
                Options:
                  -i, --input <file>     Input video file (required)
                  -o, --output <file>    Output video file (default: input_zh.mp4)
                  -c, --config <file>    Config file path (default: ./config.json)
                  -l, --lang <code>      Source language hint (en/ja/ko/auto)
                  -m, --model <name>     DeepSeek model (default: deepseek-v4-pro)
                  -k, --api-key <key>    DeepSeek API key (overrides config)
                  --mode <mode>          burn (default) | soft | both | transcript
                                         burn = hard-coded video
                                         soft = .ass subtitle file
                                         both = video + .ass
                                         transcript = .ass + .txt text transcript
                  -h, --help             Show this help

                Config (./config.json):
                  {
                    "ffmpegPath": "",
                    "whisperModelPath": "~/.video-oven/models/ggml-small.bin",
                    "deepseekApiKey": "sk-xxx",
                    "deepseekModel": "deepseek-v4-pro",
                    "defaultSourceLang": "auto"
                  }
                  (ffmpegPath and whisperModelPath are optional — auto-detected if omitted)
                                
                Prerequisites:
                  - ffmpeg (https://ffmpeg.org/download.html)
                  - Whisper model: ggml-*.bin in ./models/ or ~/.video-oven/models/
                    Download: https://huggingface.co/ggerganov/whisper.cpp/tree/main
                  - DeepSeek API key (https://platform.deepseek.com/api_keys)
                """);
    }
}
