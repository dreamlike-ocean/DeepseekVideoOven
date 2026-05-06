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
    private final Mode mode;

    public PipelineOrchestrator(DeepSeekClient client, Path modelPath, String sourceLanguage, Mode mode) {
        this.client = client;
        this.modelPath = modelPath;
        this.sourceLanguage = sourceLanguage;
        this.mode = mode;
    }

    public void process(Path input, Path videoOutput, Path assOutput) throws IOException, InterruptedException {
        var workDir = Files.createTempDirectory("video-oven-");
        try {
            try (var whisper = WhisperLib.load(modelPath)) {
                var audio = AudioExtractor.extract(input);
                var segments = SpeechRecognizer.transcribe(whisper, audio, sourceLanguage);

                if (segments.isEmpty()) {
                    System.out.println("No speech detected in video.");
                    return;
                }

                var translator = new Translator(client);
                var translated = translator.translate(segments);

                var assFile = workDir.resolve("subtitles.ass");
                SubtitleGenerator.generate(translated, assFile);

                if (mode == Mode.SOFT || mode == Mode.BOTH || mode == Mode.TRANSCRIPT) {
                    Files.copy(assFile, assOutput);
                }
                if (mode == Mode.TRANSCRIPT) {
                    writeTranscript(translated, assOutput.resolveSibling(
                            replaceExt(assOutput.getFileName().toString(), ".txt")));
                }
                if (mode == Mode.BURN || mode == Mode.BOTH) {
                    VideoBurner.burn(input, assFile, videoOutput);
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
        System.out.printf("  -> Written transcript: %s%n", txtPath);
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
