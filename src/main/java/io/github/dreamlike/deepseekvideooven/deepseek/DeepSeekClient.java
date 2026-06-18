package io.github.dreamlike.deepseekvideooven.deepseek;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dreamlike.deepseekvideooven.deepseek.dto.ChatRequest;
import io.github.dreamlike.deepseekvideooven.deepseek.dto.ChatResponse;
import io.github.dreamlike.deepseekvideooven.deepseek.dto.Message;
import io.github.dreamlike.deepseekvideooven.translation.TranslationClient;
import io.github.dreamlike.deepseekvideooven.translation.TranslationClient.ChatResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

public final class DeepSeekClient implements TranslationClient {

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int MAX_RETRIES = 3;
    private static final boolean DEBUG_HTTP = isDebugHttpEnabled();

    private final HttpClient http;
    private final String apiKey;
    private final String model;

    public DeepSeekClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public ChatResult chat(String systemPrompt, String userContent) throws IOException, InterruptedException {
        var request = ChatRequest.builder()
                .model(model)
                .messages(List.of(Message.system(systemPrompt), Message.user(userContent)))
                .temperature(0.0)
                .maxTokens(4096)
                .stream(false)
                .thinking(ChatRequest.Thinking.disabled())
                .build();

        var body = MAPPER.writeValueAsString(request);
        if (DEBUG_HTTP) {
            System.out.printf("  -> [DeepSeek Debug] 请求 URL: %s/chat/completions%n", BASE_URL);
            System.out.printf("  -> [DeepSeek Debug] 请求体:%n%s%n", body);
        }

        var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(3))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return sendWithRetry(httpRequest);
    }

    private ChatResult sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (DEBUG_HTTP) {
                    System.out.printf("  -> [DeepSeek Debug] 第 %d/%d 次请求，HTTP %d%n",
                            attempt, MAX_RETRIES, response.statusCode());
                    System.out.printf("  -> [DeepSeek Debug] 响应体:%n%s%n", response.body());
                }

                if (response.statusCode() == 200) {
                    var chatResponse = MAPPER.readValue(response.body(), ChatResponse.class);
                    if (chatResponse.choices() == null || chatResponse.choices().isEmpty()) {
                        throw new IOException("Empty response from DeepSeek API");
                    }
                    var message = chatResponse.choices().getFirst().message();
                    if ((message.content() == null || message.content().isBlank())
                            && message.reasoningContent() != null
                            && !message.reasoningContent().isBlank()) {
                        throw new IOException(
                                "DeepSeek 返回了 reasoning_content 但没有最终 content，可能仍处于 thinking mode："
                                        + preview(message.reasoningContent())
                        );
                    }
                    var usage = chatResponse.usage();
                    return new ChatResult(
                            message.content(),
                            usage != null ? usage.promptTokens() : 0,
                            usage != null ? usage.completionTokens() : 0,
                            usage != null ? usage.totalTokens() : 0
                    );
                }

                if (response.statusCode() >= 500 || response.statusCode() == 429) {
                    lastError = new IOException("DeepSeek API error " + response.statusCode());
                } else {
                    throw new IOException("DeepSeek API error " + response.statusCode() + ": " + response.body());
                }

            } catch (HttpTimeoutException e) {
                lastError = new IOException("DeepSeek API timeout", e);
            } catch (IOException e) {
                if (attempt == MAX_RETRIES) throw e;
                lastError = e;
            }

            if (attempt < MAX_RETRIES) {
                long delayMs = (long) Math.pow(2, attempt) * 1000;
                System.out.printf("  -> DeepSeek 请求失败，%d 秒后重试（第 %d/%d 次）...%n", delayMs / 1000, attempt + 1, MAX_RETRIES);
                Thread.sleep(delayMs);
            }
        }
        throw lastError;
    }

    private static boolean isDebugHttpEnabled() {
        return isTruthyEnv("VIDEO_OVEN_DEBUG_DEEPSEEK")
                || isTruthyEnv("DEEPSEEK_DEBUG_HTTP");
    }

    private static String preview(String text) {
        var normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 160 ? normalized.substring(0, 160) + "..." : normalized;
    }

    private static boolean isTruthyEnv(String name) {
        var value = System.getenv(name);
        if (value == null) {
            return false;
        }
        value = value.trim();
        return value.equals("1")
                || value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("on");
    }
}
