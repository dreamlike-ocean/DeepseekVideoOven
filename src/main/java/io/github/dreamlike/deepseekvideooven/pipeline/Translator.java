package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.deepseek.DeepSeekClient;
import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class Translator {

    private static final int BATCH_SIZE = 20;
    private static final String SEGMENT_PREFIX = "[[SEG-";
    private static final String SYSTEM_PROMPT = """
            You are a professional subtitle translator. Translate the following subtitle segments into concise, natural Chinese.
            Rules:
            1. Translate faithfully and concisely for subtitles. Do not explain, summarize, or expand.
            2. Output every segment exactly once using the same marker format: [[SEG-XXXX]] translated text
            3. Do not merge segments, do not omit segments, and do not add any extra commentary
            4. Many input segments are cut mid-phrase by ASR. Keep the original segmentation. Do not move words or meaning from one marker to another.
            5. If a segment is only a fragment, translate it as a fragment. Do not complete it with words from the next segment.
            6. Every output line must be non-empty.
            7. Preserve names, titles, identifiers, commands, code symbols, speaker labels, and recurring terms accurately.
            8. Keep tokens unchanged only when they clearly look like code or structured identifiers, such as camelCase, PascalCase used as names, snake_case, ALL_CAPS constants, dotted names, function calls, generic type syntax, code literals, or exact API/package/class names.
            9. Do not keep an English word unchanged merely because it is capitalized at the start of a sentence or segment. Ordinary words should still be translated naturally.
            10. When an identifier appears inside an otherwise natural sentence, translate only the surrounding words and keep the identifier itself unchanged.
            11. If the same identifier appears elsewhere in a clearer original form, keep that canonical identifier form consistent across the batch even if ASR introduces lowercase, spacing, or minor formatting variation.
            12. If you are unsure whether a token is an identifier, prefer natural translation unless it has strong code-like signals such as internal capitalization, digits, underscores, dots, parentheses, or repeated exact identifier use in the batch.
            13. Choose the meaning that best fits the local context. Avoid stiff, overly literal, or dictionary-style wording.
            14. Keep translations consistent within the batch.
            """;

    private final DeepSeekClient client;
    private final String extraTranslationPrompt;

    public Translator(DeepSeekClient client, String extraTranslationPrompt) {
        this.client = client;
        this.extraTranslationPrompt = extraTranslationPrompt;
    }

    public List<SubtitleSegment> translate(List<SubtitleSegment> segments) throws IOException, InterruptedException {
        System.out.println("[3/5] 翻译为中文...");
        if (segments.isEmpty()) {
            return List.of();
        }

        var batches = buildBatches(segments);
        var translated = new ArrayList<SubtitleSegment>(segments.size());
        int completedSegments = 0;

        for (var batch : batches) {
            var translatedBatch = translateBatch(batch, segments.size(), batches.size(), completedSegments);
            completedSegments += batch.segments().size();

            for (int j = 0; j < translatedBatch.translatedLines().size() && j < translatedBatch.segments().size(); j++) {
                var orig = translatedBatch.segments().get(j);
                translated.add(new SubtitleSegment(orig.t0Ms(), orig.t1Ms(), translatedBatch.translatedLines().get(j)));
            }
        }

        return translated;
    }

    private BatchTranslation translateBatch(
            BatchRequest batch,
            int totalSegments,
            int totalBatches,
            int completedSegmentsBefore
    ) throws IOException, InterruptedException {
        long startedAt = System.nanoTime();
        String batchLabel = "批次 %d/%d".formatted(batch.batchIndex() + 1, totalBatches);
        var translated = translateSegments(batch.segments(), batchLabel);
        double seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        int done = completedSegmentsBefore + batch.segments().size();
        System.out.printf(
                "  -> 批次 %d/%d 完成：%d 行，耗时 %.2fs，tokens %d（输入 %d / 输出 %d），累计 %d/%d%n",
                batch.batchIndex() + 1,
                totalBatches,
                batch.segments().size(),
                seconds,
                translated.totalTokens(),
                translated.promptTokens(),
                translated.completionTokens(),
                done,
                totalSegments
        );
        return new BatchTranslation(batch.batchIndex(), batch.segments(), translated.lines());
    }

    private String buildSystemPrompt() {
        if (extraTranslationPrompt == null || extraTranslationPrompt.isBlank()) {
            return SYSTEM_PROMPT;
        }
        return SYSTEM_PROMPT
                + "\nAdditional user instructions:\n"
                + extraTranslationPrompt.strip()
                + "\n";
    }

    private String buildPrompt(List<SubtitleSegment> batch) {
        var sb = new StringBuilder();
        sb.append("Translate each segment to Chinese and keep the same marker.\n");
        sb.append("Return exactly ").append(batch.size()).append(" non-empty output lines.\n");
        sb.append("Output format example:\n");
        sb.append("[[SEG-0001]] 这是第一行翻译\n");
        sb.append("[[SEG-0002]] 这是第二行翻译\n\n");
        sb.append("Fragment boundary example:\n");
        sb.append("[[SEG-0001]] Flexible\n");
        sb.append("[[SEG-0002]] Constructed Bodies debuted as a finalized\n");
        sb.append("Correct output:\n");
        sb.append("[[SEG-0001]] 灵活\n");
        sb.append("[[SEG-0002]] 构造体作为最终定稿的功能首次亮相\n\n");
        sb.append("Identifier preservation example:\n");
        sb.append("[[SEG-0001]] fieldName is null\n");
        sb.append("[[SEG-0002]] call validateAge(userAge) before super\n");
        sb.append("[[SEG-0003]] employee constructor\n");
        sb.append("[[SEG-0004]] Methods were added\n");
        sb.append("Correct output:\n");
        sb.append("[[SEG-0001]] fieldName 为 null\n");
        sb.append("[[SEG-0002]] 在 super 之前调用 validateAge(userAge)\n");
        sb.append("[[SEG-0003]] Employee 构造函数\n");
        sb.append("[[SEG-0004]] 添加了方法\n\n");
        sb.append("Segments:\n");
        for (int i = 0; i < batch.size(); i++) {
            sb.append(segmentMarker(i + 1))
                    .append(' ')
                    .append(batch.get(i).text())
                    .append('\n');
        }
        return sb.toString();
    }

    private TranslationResult translateSegments(List<SubtitleSegment> segments, String label) throws IOException, InterruptedException {
        var prompt = buildPrompt(segments);
        var response = client.chat(buildSystemPrompt(), prompt);

        try {
            var lines = parseResponse(response.content(), segments.size());
            return new TranslationResult(
                    lines,
                    response.promptTokens(),
                    response.completionTokens(),
                    response.totalTokens()
            );
        } catch (IllegalStateException e) {
            if (segments.size() <= 1) {
                throw new IllegalStateException(
                        label + " 解析失败：" + e.getMessage() + "；返回内容片段：" + previewResponse(response.content()),
                        e
                );
            }

            int mid = segments.size() / 2;
            int leftSize = mid;
            int rightSize = segments.size() - mid;
            System.out.printf(
                    "  -> %s 输出格式异常，拆分重试：%d -> %d + %d（返回内容片段：%s）%n",
                    label,
                    segments.size(),
                    leftSize,
                    rightSize,
                    previewResponse(response.content())
            );

            var left = translateSegments(segments.subList(0, mid), label + ".1");
            var right = translateSegments(segments.subList(mid, segments.size()), label + ".2");

            var mergedLines = new ArrayList<String>(segments.size());
            mergedLines.addAll(left.lines());
            mergedLines.addAll(right.lines());
            return new TranslationResult(
                    mergedLines,
                    response.promptTokens() + left.promptTokens() + right.promptTokens(),
                    response.completionTokens() + left.completionTokens() + right.completionTokens(),
                    response.totalTokens() + left.totalTokens() + right.totalTokens()
            );
        }
    }

    private List<BatchRequest> buildBatches(List<SubtitleSegment> segments) {
        var batches = new ArrayList<BatchRequest>();
        for (int i = 0, batchIndex = 0; i < segments.size(); i += BATCH_SIZE, batchIndex++) {
            int end = Math.min(i + BATCH_SIZE, segments.size());
            batches.add(new BatchRequest(batchIndex, segments.subList(i, end)));
        }
        return batches;
    }

    private List<String> parseResponse(String response, int expectedCount) {
        var byMarker = parseMarkedResponse(response, expectedCount);
        if (byMarker != null) {
            return byMarker;
        }

        var lines = new ArrayList<String>();
        for (var line : response.lines().toList()) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            trimmed = trimmed.replaceAll("^\\d+[.)]\\s*", "");
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }

        if (lines.size() != expectedCount) {
            throw new IllegalStateException(
                    "DeepSeek 返回的行数不匹配，期望 %d，实际 %d".formatted(expectedCount, lines.size())
            );
        }
        return lines;
    }

    private List<String> parseMarkedResponse(String response, int expectedCount) {
        if (!response.contains(SEGMENT_PREFIX)) {
            return null;
        }

        var results = new ArrayList<String>(expectedCount);
        for (int i = 0; i < expectedCount; i++) {
            results.add("");
        }

        int searchFrom = 0;
        boolean foundAny = false;
        while (true) {
            int markerStart = response.indexOf(SEGMENT_PREFIX, searchFrom);
            if (markerStart < 0) {
                break;
            }
            int markerEnd = response.indexOf("]]", markerStart);
            if (markerEnd < 0) {
                break;
            }

            int nextMarker = response.indexOf(SEGMENT_PREFIX, markerEnd + 2);
            String idText = response.substring(markerStart + SEGMENT_PREFIX.length(), markerEnd).trim();
            String content = response.substring(markerEnd + 2, nextMarker >= 0 ? nextMarker : response.length()).trim();
            content = content.replaceAll("\\s*\\n\\s*", " ").trim();

            try {
                int id = Integer.parseInt(idText);
                if (id >= 1 && id <= expectedCount) {
                    results.set(id - 1, content);
                    foundAny = true;
                }
            } catch (NumberFormatException ignored) {
            }

            searchFrom = markerEnd + 2;
        }

        if (!foundAny) {
            return null;
        }

        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).isBlank()) {
                throw new IllegalStateException(
                        "DeepSeek 返回的分段标记不完整，缺少第 %d 行".formatted(i + 1)
                );
            }
        }

        return results;
    }

    private String segmentMarker(int segmentId) {
        return "%s%04d]]".formatted(SEGMENT_PREFIX, segmentId);
    }

    private String previewResponse(String response) {
        if (response == null || response.isBlank()) {
            return "<empty>";
        }
        var normalized = response.replaceAll("\\s+", " ").trim();
        return normalized.length() > 160 ? normalized.substring(0, 160) + "..." : normalized;
    }

    private record BatchRequest(int batchIndex, List<SubtitleSegment> segments) {}

    private record BatchTranslation(int batchIndex, List<SubtitleSegment> segments, List<String> translatedLines) {}

    private record TranslationResult(List<String> lines, int promptTokens, int completionTokens, int totalTokens) {}
}
