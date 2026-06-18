package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.asr.AsrEngine;
import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;

import java.util.List;

public final class SpeechRecognizer {

    private static final double MUSIC_WARNING_RATIO = 0.7;

    private SpeechRecognizer() {}

    public static List<SubtitleSegment> transcribe(
            AsrEngine asr,
            float[] audio,
            String language
    ) {
        System.out.println("[2/5] 使用 " + asr.name() + " 转录...");
        var segments = asr.transcribe(audio, language);
        System.out.printf("  -> 识别出 %d 个文本分段%n", segments.size());
        printMusicWarningIfNeeded(segments);
        return segments;
    }

    private static void printMusicWarningIfNeeded(List<SubtitleSegment> segments) {
        if (segments.isEmpty()) {
            return;
        }

        int musicLikeCount = 0;
        for (var segment : segments) {
            if (isMusicLike(segment.text())) {
                musicLikeCount++;
            }
        }

        double ratio = (double) musicLikeCount / segments.size();
        if (ratio >= 0.999) {
            System.out.println("  -> 警告：Whisper 几乎只识别出了音乐标记，当前片段更像纯音乐或演唱内容，普通语音字幕流程可能得不到可用文稿。");
        } else if (ratio >= MUSIC_WARNING_RATIO) {
            System.out.printf(
                    "  -> 警告：%.0f%% 的分段被识别为音乐标记，当前片段可能以音乐或演唱为主，转录文本质量会明显下降。%n",
                    ratio * 100
            );
        }
    }

    private static boolean isMusicLike(String text) {
        var normalized = text.trim()
                .replace('（', '(')
                .replace('）', ')')
                .replace('【', '[')
                .replace('】', ']')
                .toLowerCase();

        return normalized.equals("[music]")
                || normalized.equals("(music)")
                || normalized.equals("music")
                || normalized.equals("[音乐]")
                || normalized.equals("(音乐)")
                || normalized.equals("音乐")
                || normalized.equals("♪")
                || normalized.equals("[♪]")
                || normalized.equals("(♪)");
    }
}
