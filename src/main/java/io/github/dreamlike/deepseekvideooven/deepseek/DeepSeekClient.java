package io.github.dreamlike.deepseekvideooven.deepseek;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dreamlike.deepseekvideooven.deepseek.dto.ChatRequest;
import io.github.dreamlike.deepseekvideooven.deepseek.dto.ChatResponse;
import io.github.dreamlike.deepseekvideooven.deepseek.dto.Message;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

public final class DeepSeekClient {

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int MAX_RETRIES = 3;

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

    public String chat(String systemPrompt, String userContent) throws IOException, InterruptedException {
        var request = ChatRequest.builder()
                .model(model)
                .messages(List.of(Message.system(systemPrompt), Message.user(userContent)))
                .temperature(0.3)
                .maxTokens(4096)
                .stream(false)
                .build();

        var body = MAPPER.writeValueAsString(request);

        var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(3))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return sendWithRetry(httpRequest);
    }

    private String sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    var chatResponse = MAPPER.readValue(response.body(), ChatResponse.class);
                    if (chatResponse.choices() == null || chatResponse.choices().isEmpty()) {
                        throw new IOException("Empty response from DeepSeek API");
                    }
                    return chatResponse.choices().getFirst().message().content();
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
                System.out.printf("  -> Retrying in %ds (attempt %d/%d)...%n", delayMs / 1000, attempt + 1, MAX_RETRIES);
                Thread.sleep(delayMs);
            }
        }
        throw lastError;
    }
}
