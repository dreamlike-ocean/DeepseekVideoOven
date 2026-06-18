package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.asr.AsrEngine;
import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;
import io.github.dreamlike.deepseekvideooven.translation.TranslationClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PipelineOrchestrator {

    public enum Mode {BURN, SOFT, BOTH, TRANSCRIPT, ASR}

    private final AsrEngine asr;
    private final TranslationClient client;
    private final String sourceLanguage;
    private final String extraPrompt;
    private final Mode mode;
    private final SubtitleGenerator.Format subtitleFormat;

    public PipelineOrchestrator(
            AsrEngine asr,
            TranslationClient client,
            String sourceLanguage,
            String extraPrompt,
            Mode mode,
            SubtitleGenerator.Format subtitleFormat
    ) {
        this.asr = asr;
        this.client = client;
        this.sourceLanguage = sourceLanguage;
        this.extraPrompt = extraPrompt;
        this.mode = mode;
        this.subtitleFormat = subtitleFormat;
    }

    public void process(Path input, Path videoOutput, Path subtitleOutput) throws IOException, InterruptedException {
        var workDir = Files.createTempDirectory("video-oven-");
        try {
            long stageStart = System.nanoTime();
            var audio = AudioExtractor.extract(input);
            printStageElapsed(stageStart);

            stageStart = System.nanoTime();
            var segments = SpeechRecognizer.transcribe(asr, audio, sourceLanguage);
            printStageElapsed(stageStart);

            if (segments.isEmpty()) {
                System.out.println("未检测到语音内容。");
                return;
            }

            if (mode == Mode.ASR) {
                stageStart = System.nanoTime();
                System.out.println("[3/3] 生成 ASR 字幕...");
                if (subtitleFormat == SubtitleGenerator.Format.ASS) {
                    SubtitleGenerator.generateAss(segments, subtitleOutput);
                } else {
                    SubtitleGenerator.generateSrt(segments, subtitleOutput);
                }
                writeTranscript(segments, subtitleOutput.resolveSibling(
                        replaceExt(subtitleOutput.getFileName().toString(), ".txt")), "已写出 ASR 文稿");
                printStageElapsed(stageStart);
                return;
            }

            stageStart = System.nanoTime();
            if (client == null) {
                throw new IllegalStateException("当前模式需要翻译后端。");
            }
            var translator = new Translator(client, extraPrompt);
            var translated = translator.translate(segments);
            var cleaned = SegmentCleaner.clean(translated);
            var transcriptSegments = mode == Mode.TRANSCRIPT
                    ? SegmentCleaner.cleanForTranscript(translated)
                    : cleaned;
            printStageElapsed(stageStart);

            stageStart = System.nanoTime();
            System.out.println("[4/5] 生成字幕...");
            var assFile = workDir.resolve("subtitles.ass");
            boolean needsAssForBurn = mode == Mode.BURN || mode == Mode.BOTH;
            boolean outputsSubtitleFile = mode == Mode.SOFT || mode == Mode.BOTH || mode == Mode.TRANSCRIPT;
            boolean outputsAssFile = outputsSubtitleFile && subtitleFormat == SubtitleGenerator.Format.ASS;

            if (needsAssForBurn || outputsAssFile) {
                SubtitleGenerator.generateAss(cleaned, assFile);
            }
            if (outputsSubtitleFile) {
                if (subtitleFormat == SubtitleGenerator.Format.ASS) {
                    Files.copy(assFile, subtitleOutput);
                } else {
                    SubtitleGenerator.generateSrt(cleaned, subtitleOutput);
                }
            }

            if (mode == Mode.TRANSCRIPT) {
                var translatedTranscriptPath = subtitleOutput.resolveSibling(
                        replaceExt(subtitleOutput.getFileName().toString(), ".txt"));
                var originalTranscriptPath = subtitleOutput.resolveSibling(
                        replaceExt(subtitleOutput.getFileName().toString(), ".orig.txt"));
                writeTranscript(transcriptSegments, translatedTranscriptPath, "已写出中文文稿");
                writeTranscript(segments, originalTranscriptPath, "已写出原始文稿");
            }
            printStageElapsed(stageStart);

            if (mode == Mode.BURN || mode == Mode.BOTH) {
                stageStart = System.nanoTime();
                VideoBurner.burn(input, assFile, videoOutput);
                printStageElapsed(stageStart);
            } else {
                System.out.println("[5/5] 跳过烧录字幕到视频...");
                System.out.println("  -> 当前模式不包含视频烧录。");
            }
        } finally {
            if (mode == Mode.BURN) {
                deleteRecursive(workDir);
            }
        }
    }

    private static void writeTranscript(List<SubtitleSegment> segments, Path txtPath, String label) throws IOException {
        var sb = new StringBuilder();
        for (var seg : segments) {
            sb.append("[").append(format(seg.t0Ms())).append(" -> ").append(format(seg.t1Ms())).append("]\n");
            sb.append(seg.text()).append("\n\n");
        }
        Files.writeString(txtPath, sb.toString());
        System.out.printf("  -> %s：%s%n", label, txtPath);
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
