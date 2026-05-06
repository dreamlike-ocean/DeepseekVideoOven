package io.github.dreamlike.deepseekvideooven.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ChatRequest(
        String model,
        List<Message> messages,
        Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens,
        Boolean stream,
        Thinking thinking
) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String model = "deepseek-v4-pro";
        private List<Message> messages;
        private Double temperature = 0.3;
        private Integer maxTokens = 4096;
        private Boolean stream = false;
        private Thinking thinking = Thinking.disabled();

        public Builder model(String v) { model = v; return this; }
        public Builder messages(List<Message> v) { messages = v; return this; }
        public Builder temperature(Double v) { temperature = v; return this; }
        public Builder maxTokens(Integer v) { maxTokens = v; return this; }
        public Builder stream(Boolean v) { stream = v; return this; }
        public Builder thinking(Thinking v) { thinking = v; return this; }
        public ChatRequest build() { return new ChatRequest(model, messages, temperature, maxTokens, stream, thinking); }
    }

    public record Thinking(String type) {
        public static Thinking disabled() {
            return new Thinking("disabled");
        }
    }
}
