package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;
import io.github.dreamlike.deepseekvideooven.whisper.WhisperLib;

import java.util.List;

public final class SpeechRecognizer {

    private SpeechRecognizer() {}

    public static List<SubtitleSegment> transcribe(WhisperLib whisper, float[] audio) {
        System.out.println("[2/5] Transcribing with whisper...");
        var segments = whisper.transcribe(audio);
        System.out.printf("  -> Recognized %d text segments%n", segments.size());
        return segments;
    }
}
