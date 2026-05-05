package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.deepseek.DeepSeekClient;
import io.github.dreamlike.deepseekvideooven.whisper.WhisperLib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PipelineOrchestrator {

    private final DeepSeekClient client;
    private final Path modelPath;

    public PipelineOrchestrator(DeepSeekClient client, Path modelPath) {
        this.client = client;
        this.modelPath = modelPath;
    }

    public void process(Path input, Path output) throws IOException, InterruptedException {
        var workDir = Files.createTempDirectory("video-oven-");
        try {
            try (var whisper = WhisperLib.load(modelPath)) {
                var audio = AudioExtractor.extract(input);
                var segments = SpeechRecognizer.transcribe(whisper, audio);

                if (segments.isEmpty()) {
                    System.out.println("No speech detected in video.");
                    return;
                }

                var translator = new Translator(client);
                var translated = translator.translate(segments);

                var assFile = workDir.resolve("subtitles.ass");
                SubtitleGenerator.generate(translated, assFile);

                VideoBurner.burn(input, assFile, output);
            }
        } finally {
            deleteRecursive(workDir);
        }
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
