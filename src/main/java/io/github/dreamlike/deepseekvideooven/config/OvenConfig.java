package io.github.dreamlike.deepseekvideooven.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OvenConfig(
        @JsonProperty("ffmpegPath") String ffmpegPath,
        @JsonProperty("sourceLang") String sourceLang,
        @JsonProperty("asr") Asr asr,
        @JsonProperty("translation") Translation translation
) {
    public OvenConfig {
        if (sourceLang == null || sourceLang.isBlank()) {
            sourceLang = "auto";
        }
        if (asr == null) {
            asr = new Asr(null, null, null);
        }
        if (translation == null) {
            translation = new Translation(null, null, null, null);
        }
    }

    public static OvenConfig empty() {
        return new OvenConfig(null, null, null, null);
    }

    public record Asr(
            @JsonProperty("backend") String backend,
            @JsonProperty("initialPrompt") String initialPrompt,
            @JsonProperty("whisper") Whisper whisper
    ) {
        public Asr {
            if (backend == null || backend.isBlank()) {
                backend = "whisper";
            }
            if (initialPrompt != null && initialPrompt.isBlank()) {
                initialPrompt = null;
            }
            if (whisper == null) {
                whisper = new Whisper(null);
            }
        }
    }

    public record Whisper(@JsonProperty("modelPath") String modelPath) {
    }

    public record Translation(
            @JsonProperty("backend") String backend,
            @JsonProperty("extraPrompt") String extraPrompt,
            @JsonProperty("hunyuan") Hunyuan hunyuan,
            @JsonProperty("deepseek") DeepSeek deepseek
    ) {
        public Translation {
            if (backend == null || backend.isBlank()) {
                backend = "hunyuan";
            }
            if (extraPrompt != null && extraPrompt.isBlank()) {
                extraPrompt = null;
            }
            if (hunyuan == null) {
                hunyuan = new Hunyuan(null, null, null, null, null, null, null, null, null);
            }
            if (deepseek == null) {
                deepseek = new DeepSeek(null, null);
            }
        }
    }

    public record Hunyuan(
            @JsonProperty("modelPath") String modelPath,
            @JsonProperty("contextSize") Integer contextSize,
            @JsonProperty("gpuLayers") Integer gpuLayers,
            @JsonProperty("threads") Integer threads,
            @JsonProperty("maxTokens") Integer maxTokens,
            @JsonProperty("temperature") Float temperature,
            @JsonProperty("topP") Float topP,
            @JsonProperty("topK") Integer topK,
            @JsonProperty("repeatPenalty") Float repeatPenalty
    ) {
        public Hunyuan {
            if (contextSize == null || contextSize <= 0) {
                contextSize = 4096;
            }
            if (gpuLayers == null) {
                gpuLayers = 999;
            }
            if (threads == null) {
                threads = 0;
            }
            if (maxTokens == null || maxTokens <= 0) {
                maxTokens = 4096;
            }
            if (temperature == null || temperature < 0) {
                temperature = 0.0f;
            }
            if (topP == null || topP <= 0 || topP > 1) {
                topP = 0.9f;
            }
            if (topK == null) {
                topK = 40;
            }
            if (repeatPenalty == null || repeatPenalty <= 0) {
                repeatPenalty = 1.0f;
            }
        }
    }

    public record DeepSeek(
            @JsonProperty("apiKey") String apiKey,
            @JsonProperty("model") String model
    ) {
        public DeepSeek {
            if (model == null || model.isBlank()) {
                model = "deepseek-v4-pro";
            }
        }
    }
}
