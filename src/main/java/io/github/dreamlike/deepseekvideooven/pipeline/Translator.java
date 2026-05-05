package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.deepseek.DeepSeekClient;
import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class Translator {

    private static final String SYSTEM_PROMPT = """
            You are a professional subtitle translator. Translate the following subtitles into Chinese.
            Rules:
            1. Keep the EXACT same number of lines in your response
            2. Each line must be a direct translation of the corresponding source line
            3. Do NOT add numbering, prefixes, or any extra text
            4. Do NOT include the original text in your response
            5. Preserve any speaker labels or sound descriptions as-is
            """;

    private final DeepSeekClient client;

    public Translator(DeepSeekClient client) {
        this.client = client;
    }

    public List<SubtitleSegment> translate(List<SubtitleSegment> segments) throws IOException, InterruptedException {
        System.out.println("[3/5] Translating to Chinese...");
        int batchSize = 20;
        var translated = new ArrayList<SubtitleSegment>();

        for (int i = 0; i < segments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segments.size());
            var batch = segments.subList(i, end);

            var prompt = buildPrompt(batch, i);
            var response = client.chat(SYSTEM_PROMPT, prompt);
            var translatedLines = parseResponse(response, batch.size());

            for (int j = 0; j < translatedLines.size() && j < batch.size(); j++) {
                var orig = batch.get(j);
                translated.add(new SubtitleSegment(orig.t0Ms(), orig.t1Ms(), translatedLines.get(j)));
            }

            System.out.printf("  -> Translated %d/%d segments%n", end, segments.size());
        }

        return translated;
    }

    private String buildPrompt(List<SubtitleSegment> batch, int offset) {
        var sb = new StringBuilder();
        sb.append("Translate each line below to Chinese:\n\n");
        for (int i = 0; i < batch.size(); i++) {
            sb.append(batch.get(i).text()).append('\n');
        }
        sb.append("\nOutput ONLY the translated lines, one per line, same order:");
        return sb.toString();
    }

    private List<String> parseResponse(String response, int expectedCount) {
        var lines = new ArrayList<String>();
        for (var line : response.lines().toList()) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // Remove any accidental numbering like "1." or "1."
            trimmed = trimmed.replaceAll("^\\d+[.)]\\s*", "");
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }

        // Pad or truncate to match expected count
        while (lines.size() < expectedCount) {
            lines.add("");
        }
        if (lines.size() > expectedCount) {
            lines = new ArrayList<>(lines.subList(0, expectedCount));
        }
        return lines;
    }
}
