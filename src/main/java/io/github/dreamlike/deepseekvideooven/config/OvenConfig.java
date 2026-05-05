package io.github.dreamlike.deepseekvideooven.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OvenConfig(
        @JsonProperty("ffmpegPath") String ffmpegPath,
        @JsonProperty("whisperModelPath") String whisperModelPath,
        @JsonProperty("deepseekApiKey") String deepseekApiKey,
        @JsonProperty("deepseekModel") String deepseekModel,
        @JsonProperty("defaultSourceLang") String defaultSourceLang
) {
    public OvenConfig {
        if (deepseekModel == null || deepseekModel.isBlank()) {
            deepseekModel = "deepseek-v4-pro";
        }
        if (defaultSourceLang == null || defaultSourceLang.isBlank()) {
            defaultSourceLang = "auto";
        }
    }
}
