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
                    "ffmpeg not found. Install it: brew install ffmpeg");
        }
        if (modelPath == null) {
            var home = System.getProperty("user.home");
            var modelsDir = Path.of(home, ".video-oven", "models");
            throw new IllegalStateException(
                    "Whisper model not found. Download ggml-small.bin to " + modelsDir +
                    " or configure whisperModelPath in ~/.video-oven/config.json");
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
        var knownPaths = System.getProperty("os.name").toLowerCase().contains("win")
                ? List.of("C:\\ffmpeg\\bin\\ffmpeg.exe")
                : List.of("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg");
        for (var path : knownPaths) {
            if (Files.exists(Path.of(path))) return path;
        }
        if (canRun("ffmpeg", "-version")) return "ffmpeg";
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
        var home = System.getProperty("user.home");
        for (var name : List.of("ggml-large.bin", "ggml-medium.bin", "ggml-small.bin", "ggml-base.bin", "ggml-tiny.bin")) {
            var p = Path.of(home, ".video-oven", "models", name);
            if (Files.exists(p)) return p.toString();
        }
        for (var name : List.of("ggml-large.bin", "ggml-medium.bin", "ggml-small.bin", "ggml-base.bin")) {
            var p = Path.of(home, ".cache", "whisper", name);
            if (Files.exists(p)) return p.toString();
        }
        return null;
    }
}
