package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;
import io.github.dreamlike.deepseekvideooven.whisper.WhisperLib;

import java.util.List;

public final class SpeechRecognizer {

    private SpeechRecognizer() {}

    public static List<SubtitleSegment> transcribe(WhisperLib whisper, float[] audio, String language) {
        System.out.println("[2/5] 使用 Whisper 转录...");
        var segments = whisper.transcribe(audio, language);
        System.out.printf("  -> 识别出 %d 个文本分段%n", segments.size());
        return segments;
    }
}
