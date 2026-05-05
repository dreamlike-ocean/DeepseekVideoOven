package io.github.dreamlike.deepseekvideooven.deepseek.dto;

public record Message(String role, String content) {
    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message user(String content) {
        return new Message("user", content);
    }
}
