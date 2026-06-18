package io.github.dreamlike.deepseekvideooven.hunyuan;

import io.github.dreamlike.deepseekvideooven.translation.TranslationClient;

import java.io.IOException;
import java.nio.file.Path;

public final class HunyuanTranslationClient implements TranslationClient {

    private final HunyuanLib hunyuan;
    private final int maxTokens;
    private final float temperature;
    private final float topP;
    private final int topK;
    private final float repeatPenalty;

    private HunyuanTranslationClient(
            HunyuanLib hunyuan,
            int maxTokens,
            float temperature,
            float topP,
            int topK,
            float repeatPenalty
    ) {
        this.hunyuan = hunyuan;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.topP = topP;
        this.topK = topK;
        this.repeatPenalty = repeatPenalty;
    }

    public static HunyuanTranslationClient load(
            Path modelPath,
            int contextSize,
            int gpuLayers,
            int threads,
            int maxTokens,
            float temperature,
            float topP,
            int topK,
            float repeatPenalty
    ) {
        return new HunyuanTranslationClient(
                HunyuanLib.load(modelPath, contextSize, gpuLayers, threads),
                maxTokens, temperature, topP, topK, repeatPenalty
        );
    }

    @Override
    public synchronized ChatResult chat(String systemPrompt, String userContent) throws IOException {
        var content = hunyuan.chat(systemPrompt, userContent, maxTokens, temperature, topP, topK, repeatPenalty);
        return new ChatResult(content, 0, 0, 0);
    }

    @Override
    public int maxConcurrentBatches() {
        return 1;
    }

    @Override
    public void close() {
        hunyuan.close();
    }
}
