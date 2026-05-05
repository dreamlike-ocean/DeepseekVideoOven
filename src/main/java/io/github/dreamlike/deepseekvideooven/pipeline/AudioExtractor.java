package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.ffmpeg.FFmpegBridge;

import java.nio.file.Path;

public final class AudioExtractor {

    private AudioExtractor() {}

    public static float[] extract(Path videoPath) {
        System.out.println("[1/5] Extracting audio...");
        var samples = FFmpegBridge.extractAudio(videoPath);
        double duration = (double) samples.length / 16000.0;
        System.out.printf("  -> Extracted %.1f seconds of audio (%d samples)%n", duration, samples.length);
        return samples;
    }
}
