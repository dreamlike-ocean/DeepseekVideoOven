package io.github.dreamlike.deepseekvideooven.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ToolDetector {

    private ToolDetector() {
    }

    public record ResolvedConfig(
            String ffmpegPath,
            String sourceLang,
            ResolvedAsr asr,
            ResolvedTranslation translation
    ) {
    }

    public record ResolvedAsr(
            String backend,
            String modelPath,
            String initialPrompt
    ) {
    }

    public record ResolvedTranslation(
            String backend,
            String extraPrompt,
            ResolvedHunyuan hunyuan,
            ResolvedDeepSeek deepseek
    ) {
    }

    public record ResolvedHunyuan(
            String modelPath,
            int contextSize,
            int gpuLayers,
            int threads,
            int maxTokens,
            float temperature,
            float topP,
            int topK,
            float repeatPenalty
    ) {
    }

    public record ResolvedDeepSeek(String apiKey, String model) {
    }

    public static ResolvedConfig resolve(OvenConfig config) {
        return resolve(config, true);
    }

    public static ResolvedConfig resolve(OvenConfig config, boolean requireTranslation) {
        var ffmpegPath = resolveFfmpeg(config.ffmpegPath());
        var asr = config.asr();
        var translation = config.translation();
        var asrBackend = normalizeBackend(asr.backend(), "asr.backend", List.of("whisper"));
        var translationBackend = normalizeBackend(translation.backend(), "translation.backend", List.of("hunyuan", "deepseek"));

        var whisperModelPath = asrBackend.equals("whisper") ? resolveWhisperModel(asr.whisper().modelPath()) : asr.whisper().modelPath();
        var asrModelPath = whisperModelPath;

        var hunyuan = translation.hunyuan();
        var hunyuanModelPath = requireTranslation && translationBackend.equals("hunyuan")
                ? resolveHunyuanModel(hunyuan.modelPath())
                : hunyuan.modelPath();
        var deepseek = translation.deepseek();
        var deepseekApiKey = firstNonBlank(deepseek.apiKey(), System.getenv("DEEPSEEK_API_KEY"));

        if (ffmpegPath == null) {
            throw new IllegalStateException("ffmpeg not found. Install it: https://ffmpeg.org/download.html");
        }
        if (asrBackend.equals("whisper") && whisperModelPath == null) {
            throw new IllegalStateException("""
                    Whisper model not found. Download a ggml model file:
                      - ggml-tiny.bin  (~75 MB)  for fast/lightweight
                      - ggml-small.bin (~466 MB) recommended
                      - ggml-medium.bin (~1.5 GB) for better accuracy
                    URL: https://huggingface.co/ggerganov/whisper.cpp/tree/main
                    Place it in ./models/ or ~/.video-oven/models/
                    Or set asr.whisper.modelPath in ./config.json""");
        }
        if (requireTranslation && translationBackend.equals("hunyuan") && hunyuanModelPath == null) {
            throw new IllegalStateException("""
                    Hunyuan GGUF model not found.
                    Set translation.hunyuan.modelPath in ./config.json.""");
        }
        if (requireTranslation && translationBackend.equals("deepseek") && (deepseekApiKey == null || deepseekApiKey.isBlank())) {
            throw new IllegalStateException("""
                    DeepSeek API Key not configured.
                    Set translation.deepseek.apiKey in ./config.json or DEEPSEEK_API_KEY.""");
        }

        return new ResolvedConfig(
                ffmpegPath,
                config.sourceLang(),
                new ResolvedAsr(asrBackend, asrModelPath, asr.initialPrompt()),
                new ResolvedTranslation(
                        translationBackend,
                        translation.extraPrompt(),
                        new ResolvedHunyuan(
                                hunyuanModelPath,
                                hunyuan.contextSize(),
                                hunyuan.gpuLayers(),
                                hunyuan.threads(),
                                hunyuan.maxTokens(),
                                hunyuan.temperature(),
                                hunyuan.topP(),
                                hunyuan.topK(),
                                hunyuan.repeatPenalty()
                        ),
                        new ResolvedDeepSeek(deepseekApiKey, deepseek.model())
                )
        );
    }

    private static String normalizeBackend(String backend, String name, List<String> allowed) {
        var normalized = backend.toLowerCase();
        if (!allowed.contains(normalized)) {
            throw new IllegalStateException(name + " must be one of " + allowed + ", but was: " + backend);
        }
        return normalized;
    }

    private static String resolveFfmpeg(String configured) {
        if (configured != null && !configured.isBlank()) {
            var path = expandPath(configured);
            if (Files.exists(path)) return path.toString();
        }
        if (ffmpegInPath()) return "ffmpeg";
        var isWin = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> knownPaths;
        if (isWin) {
            knownPaths = List.of(
                    "C:\\ffmpeg\\bin\\ffmpeg.exe",
                    "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe"
            );
        } else {
            knownPaths = List.of(
                    "/opt/homebrew/bin/ffmpeg",
                    "/usr/local/bin/ffmpeg",
                    "/usr/bin/ffmpeg",
                    "/snap/bin/ffmpeg"
            );
        }
        for (var path : knownPaths) {
            if (Files.exists(Path.of(path))) return path;
        }
        return null;
    }

    private static boolean ffmpegInPath() {
        try {
            return new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String resolveWhisperModel(String configured) {
        if (configured != null && !configured.isBlank()) {
            var path = expandPath(configured);
            if (Files.exists(path)) return path.toString();
        }
        var modelNames = List.of("ggml-large-v3.bin", "ggml-medium.bin", "ggml-small.bin",
                "ggml-base.bin", "ggml-tiny.bin");
        var home = System.getProperty("user.home");
        var searchDirs = List.of(
                Path.of(""),
                Path.of("models"),
                Path.of(home, ".video-oven", "models"),
                Path.of(home, ".cache", "whisper"));
        for (var dir : searchDirs) {
            for (var name : modelNames) {
                var p = dir.resolve(name);
                if (Files.exists(p)) return p.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static String resolveHunyuanModel(String configured) {
        if (configured != null && !configured.isBlank()) {
            var path = expandPath(configured);
            if (Files.exists(path)) return path.toString();
        }
        var home = System.getProperty("user.home");
        var searchDirs = List.of(
                Path.of("models"),
                Path.of(home, ".video-oven", "models"),
                Path.of(home, ".cache", "video-oven")
        );
        for (var dir : searchDirs) {
            var discovered = findFirstMatchingGguf(dir, "hy-mt");
            if (discovered != null) return discovered.toString();
            discovered = findFirstMatchingGguf(dir, "hunyuan");
            if (discovered != null) return discovered.toString();
        }
        return null;
    }

    private static Path findFirstMatchingGguf(Path dir, String needle) {
        if (!Files.isDirectory(dir)) return null;
        try (var paths = Files.list(dir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".gguf"))
                    .filter(path -> containsIgnoreCase(path.getFileName().toString(), needle))
                    .findFirst()
                    .map(Path::toAbsolutePath)
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean containsIgnoreCase(String name, String needle) {
        return name.toLowerCase().contains(needle);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return null;
    }

    private static Path expandPath(String path) {
        if (path.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (path.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), path.substring(2));
        }
        return Path.of(path);
    }
}
