package io.github.dreamlike.deepseekvideooven.translation;

import java.io.IOException;

public interface TranslationClient extends AutoCloseable {

    ChatResult chat(String systemPrompt, String userContent) throws IOException, InterruptedException;

    default int maxConcurrentBatches() {
        return 10;
    }

    @Override
    default void close() {}

    record ChatResult(String content, int promptTokens, int completionTokens, int totalTokens) {}
}
