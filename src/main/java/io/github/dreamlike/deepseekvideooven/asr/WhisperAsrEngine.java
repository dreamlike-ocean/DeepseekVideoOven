package io.github.dreamlike.deepseekvideooven.asr;

import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;
import io.github.dreamlike.deepseekvideooven.whisper.WhisperLib;

import java.nio.file.Path;
import java.util.List;

public final class WhisperAsrEngine implements AsrEngine {

    private final WhisperLib whisper;
    private final String initialPrompt;

    private WhisperAsrEngine(WhisperLib whisper, String initialPrompt) {
        this.whisper = whisper;
        this.initialPrompt = initialPrompt;
    }

    public static WhisperAsrEngine load(Path modelPath, String initialPrompt) {
        return new WhisperAsrEngine(WhisperLib.load(modelPath), initialPrompt);
    }

    @Override
    public String name() {
        return "Whisper";
    }

    @Override
    public List<SubtitleSegment> transcribe(float[] audio, String language) {
        return whisper.transcribe(audio, language, initialPrompt);
    }

    @Override
    public void close() {
        whisper.close();
    }
}
