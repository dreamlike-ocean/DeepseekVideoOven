package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.deepseek.DeepSeekClient;
import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;
import io.github.dreamlike.deepseekvideooven.whisper.WhisperLib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PipelineOrchestrator {

    public enum Mode { BURN, SOFT, BOTH, TRANSCRIPT }

    private final DeepSeekClient client;
    private final Path modelPath;
    private final String sourceLanguage;
    private final String extraTranslationPrompt;
    private final Mode mode;

    public PipelineOrchestrator(
            DeepSeekClient client,
            Path modelPath,
            String sourceLanguage,
            String extraTranslationPrompt,
            Mode mode
    ) {
        this.client = client;
        this.modelPath = modelPath;
        this.sourceLanguage = sourceLanguage;
        this.extraTranslationPrompt = extraTranslationPrompt;
        this.mode = mode;
    }

    public void process(Path input, Path videoOutput, Path assOutput) throws IOException, InterruptedException {
        var workDir = Files.createTempDirectory("video-oven-");
        try {
            try (var whisper = WhisperLib.load(modelPath)) {
                long stageStart = System.nanoTime();
                var audio = AudioExtractor.extract(input);
                printStageElapsed(stageStart);

                stageStart = System.nanoTime();
                var segments = SpeechRecognizer.transcribe(whisper, audio, sourceLanguage);
                printStageElapsed(stageStart);

                if (segments.isEmpty()) {
                    System.out.println("未检测到语音内容。");
                    return;
                }

                stageStart = System.nanoTime();
                var translator = new Translator(client, extraTranslationPrompt);
                var translated = translator.translate(segments);
                var cleaned = SegmentCleaner.clean(translated);
                var transcriptSegments = mode == Mode.TRANSCRIPT
                        ? SegmentCleaner.cleanForTranscript(translated)
                        : cleaned;
                printStageElapsed(stageStart);

                stageStart = System.nanoTime();
                var assFile = workDir.resolve("subtitles.ass");
                SubtitleGenerator.generate(cleaned, assFile);

                if (mode == Mode.SOFT || mode == Mode.BOTH || mode == Mode.TRANSCRIPT) {
                    Files.copy(assFile, assOutput);
                }
                if (mode == Mode.TRANSCRIPT) {
                    writeTranscript(transcriptSegments, assOutput.resolveSibling(
                            replaceExt(assOutput.getFileName().toString(), ".txt")));
                }
                printStageElapsed(stageStart);

                if (mode == Mode.BURN || mode == Mode.BOTH) {
                    stageStart = System.nanoTime();
                    VideoBurner.burn(input, assFile, videoOutput);
                    printStageElapsed(stageStart);
                }
            }
        } finally {
            if (mode == Mode.BURN) {
                deleteRecursive(workDir);
            }
        }
    }

    private static void writeTranscript(List<SubtitleSegment> segments, Path txtPath) throws IOException {
        var sb = new StringBuilder();
        for (var seg : segments) {
            sb.append("[").append(format(seg.t0Ms())).append(" -> ").append(format(seg.t1Ms())).append("]\n");
            sb.append(seg.text()).append("\n\n");
        }
        Files.writeString(txtPath, sb.toString());
        System.out.printf("  -> 已写出文稿：%s%n", txtPath);
    }

    private static void printStageElapsed(long startedAtNanos) {
        double seconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0;
        System.out.printf("  -> 耗时 %.2f 秒%n", seconds);
    }

    private static String format(long ms) {
        long h = ms / 3600000, m = (ms % 3600000) / 60000;
        long s = (ms % 60000) / 1000, cs = (ms % 1000) / 10;
        return "%d:%02d:%02d.%02d".formatted(h, m, s, cs);
    }

    private static String replaceExt(String filename, String ext) {
        var dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) + ext : filename + ext;
    }

    private static void deleteRecursive(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    files.forEach(PipelineOrchestrator::deleteRecursive);
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
        }
    }
}
