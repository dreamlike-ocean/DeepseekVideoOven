package io.github.dreamlike.deepseekvideooven.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ToolDetector {

    private ToolDetector() {}

    public record ResolvedConfig(
            String ffmpegPath,
            String whisperModelPath,
            String deepseekApiKey,
            String deepseekModel,
            String defaultSourceLang
    ) {}

    public static ResolvedConfig resolve(OvenConfig config) {
        var ffmpegPath = resolveFfmpeg(config.ffmpegPath());
        var modelPath = resolveModel(config.whisperModelPath());

        if (ffmpegPath == null) {
            throw new IllegalStateException(
                    "ffmpeg not found. Install it: https://ffmpeg.org/download.html");
        }
        if (modelPath == null) {
            throw new IllegalStateException("""
                    Whisper model not found. Download a ggml model file:
                      - ggml-tiny.bin  (~75 MB)  for fast/lightweight
                      - ggml-small.bin (~466 MB) recommended
                      - ggml-medium.bin (~1.5 GB) for better accuracy
                    URL: https://huggingface.co/ggerganov/whisper.cpp/tree/main
                    Place it in ./models/ or ~/.video-oven/models/
                    Or set whisperModelPath in ./config.json""");
        }

        return new ResolvedConfig(
                ffmpegPath, modelPath,
                config.deepseekApiKey(),
                config.deepseekModel(),
                config.defaultSourceLang()
        );
    }

    private static String resolveFfmpeg(String configured) {
        if (configured != null && !configured.isBlank()) {
            if (Files.exists(Path.of(configured))) return configured;
        }
        if (canRun("ffmpeg", "-version")) return "ffmpeg";
        var isWin = System.getProperty("os.name").toLowerCase().contains("win");
        var knownPaths = isWin
                ? List.of(
                    "C:\\ffmpeg\\bin\\ffmpeg.exe",
                    "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe")
                : List.of(
                    "/opt/homebrew/bin/ffmpeg",
                    "/usr/local/bin/ffmpeg",
                    "/usr/bin/ffmpeg",
                    "/snap/bin/ffmpeg");
        for (var path : knownPaths) {
            if (Files.exists(Path.of(path))) return path;
        }
        return null;
    }

    private static boolean canRun(String... cmd) {
        try {
            return new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String resolveModel(String configured) {
        if (configured != null && !configured.isBlank()) {
            if (Files.exists(Path.of(configured))) return configured;
        }
        var modelNames = List.of("ggml-large-v3.bin", "ggml-medium.bin", "ggml-small.bin",
                "ggml-base.bin", "ggml-tiny.bin");
        var home = System.getProperty("user.home");
        var searchDirs = List.of(
                Path.of(""),                           // current dir
                Path.of("models"),                     // ./models/
                Path.of(home, ".video-oven", "models"), // ~/.video-oven/models/
                Path.of(home, ".cache", "whisper"));    // ~/.cache/whisper/
        for (var dir : searchDirs) {
            for (var name : modelNames) {
                var p = dir.resolve(name);
                if (Files.exists(p)) return p.toAbsolutePath().toString();
            }
        }
        return null;
    }
}
