package io.github.dreamlike.deepseekvideooven.ffmpeg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class FFmpegBridge {

    private static String ffmpeg = findFfmpeg();

    private FFmpegBridge() {}

    public static void configure(String ffmpegPath) {
        if (ffmpegPath != null && !ffmpegPath.isBlank()) {
            ffmpeg = ffmpegPath;
        }
    }

    private static String findFfmpeg() {
        try {
            var pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            var p = pb.start();
            var out = new String(p.getInputStream().readAllBytes());
            p.waitFor(10, TimeUnit.SECONDS);
            if (out.contains("ffmpeg version")) return "ffmpeg";
        } catch (Exception ignored) {}
        throw new RuntimeException("ffmpeg not found on PATH");
    }

    public static float[] extractAudio(Path videoPath) {
        var cmd = List.of(
                ffmpeg,
                "-i", videoPath.toString(),
                "-vn",
                "-f", "f32le",
                "-ar", "16000",
                "-ac", "1",
                "-nostdin",
                "-hide_banner",
                "-loglevel", "error",
                "pipe:"
        );

        Process p;
        try {
            p = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start ffmpeg for audio extraction", e);
        }

        var stderr = slurpAsync(p.getErrorStream());

        byte[] raw;
        try (var in = p.getInputStream()) {
            raw = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read ffmpeg audio output", e);
        }

        int exit;
        try {
            exit = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for ffmpeg", e);
        }

        String errText = stderr.join();
        if (exit != 0 || raw.length == 0) {
            throw new RuntimeException("ffmpeg audio extraction failed (exit " + exit + "): " + errText);
        }

        var buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] samples = new float[raw.length / 4];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buf.getFloat();
        }
        return samples;
    }

    public static void burnSubtitles(Path videoPath, Path assPath, Path outputPath) {
        String assAbs = assPath.toAbsolutePath().toString();
        String filter = "subtitles=" + escapeFilterPath(assAbs)
                + ":force_style='FontName=Arial,FontSize=24,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,Outline=2'";

        var cmd = List.of(
                ffmpeg,
                "-i", videoPath.toString(),
                "-vf", filter,
                "-c:v", "libx264",
                "-c:a", "copy",
                "-nostdin",
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                outputPath.toString()
        );

        Process p;
        try {
            p = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start ffmpeg for subtitle burning", e);
        }

        var stderr = slurpAsync(p.getErrorStream());

        int exit;
        try {
            exit = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for ffmpeg", e);
        }

        String errText = stderr.join();
        if (exit != 0) {
            throw new RuntimeException("ffmpeg subtitle burning failed (exit " + exit + "): " + errText);
        }
    }

    private static String escapeFilterPath(String path) {
        return path.replace("\\", "\\\\")
                   .replace(":", "\\:")
                   .replace("'", "\\'");
    }

    private static StderrReader slurpAsync(InputStream stderr) {
        var reader = new StderrReader(stderr);
        var t = new Thread(reader, "ffmpeg-stderr");
        t.setDaemon(true);
        t.start();
        return reader;
    }

    private static final class StderrReader implements Runnable {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private volatile boolean done;

        StderrReader(InputStream in) { this.in = in; }

        @Override
        public void run() {
            try (in) {
                in.transferTo(buf);
            } catch (IOException ignored) {}
            done = true;
        }

        String join() {
            try {
                while (!done) Thread.onSpinWait();
            } catch (Exception ignored) {}
            return buf.toString();
        }
    }
}
