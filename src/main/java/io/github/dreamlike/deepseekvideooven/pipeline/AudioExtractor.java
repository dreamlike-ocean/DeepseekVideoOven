package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.ffmpeg.FFmpegBridge;

import java.nio.file.Path;

public final class AudioExtractor {

    private AudioExtractor() {}

    public static float[] extract(Path videoPath) {
        System.out.println("[1/5] 提取音频...");
        var samples = FFmpegBridge.extractAudio(videoPath);
        double duration = (double) samples.length / 16000.0;
        System.out.printf("  -> 已提取 %.1f 秒音频（%d 个采样点）%n", duration, samples.length);
        return samples;
    }
}
