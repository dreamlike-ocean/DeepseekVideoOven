package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.ffmpeg.FFmpegBridge;

import java.nio.file.Path;

public final class VideoBurner {

    private VideoBurner() {}

    public static void burn(Path videoPath, Path assPath, Path outputPath) {
        System.out.println("[5/5] Burning subtitles into video...");
        FFmpegBridge.burnSubtitles(videoPath, assPath, outputPath);
        System.out.printf("  -> Output: %s%n", outputPath);
    }
}
