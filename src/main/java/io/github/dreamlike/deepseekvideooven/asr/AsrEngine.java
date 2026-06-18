package io.github.dreamlike.deepseekvideooven.asr;

import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;

import java.util.List;

public interface AsrEngine extends AutoCloseable {

    String name();

    List<SubtitleSegment> transcribe(float[] audio, String language);

    @Override
    default void close() {}
}
