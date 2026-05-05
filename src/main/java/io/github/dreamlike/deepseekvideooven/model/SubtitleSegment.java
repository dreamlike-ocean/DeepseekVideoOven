package io.github.dreamlike.deepseekvideooven.model;

public record SubtitleSegment(long t0Ms, long t1Ms, String text) {

    public String toAssEvent(int index, int playResX, int playResY) {
        var format = "Dialogue: %d,%s,%s,Default,,0,0,0,,%s";
        return format.formatted(index, formatTime(t0Ms), formatTime(t1Ms), text);
    }

    static String formatTime(long ms) {
        long h = ms / 3600000;
        long m = (ms % 3600000) / 60000;
        long s = (ms % 60000) / 1000;
        long cs = (ms % 1000) / 10;
        return "%d:%02d:%02d.%02d".formatted(h, m, s, cs);
    }
}
