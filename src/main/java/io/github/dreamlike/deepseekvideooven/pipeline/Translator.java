package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.deepseek.DeepSeekClient;
import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class Translator {

    private static final int BATCH_SIZE = 20;
    private static final int MAX_CONCURRENT_BATCHES = 10;
    private static final String SEGMENT_PREFIX = "[[SEG-";
    private static final String SYSTEM_PROMPT = """
            You are a professional subtitle translator. Translate the following subtitle segments into concise, natural Chinese.
            Rules:
            1. Translate faithfully and concisely for subtitles. Do not explain, summarize, or expand.
            2. Output every segment exactly once using the same marker format: [[SEG-XXXX]] translated text
            3. Do not merge segments, do not omit segments, and do not add any extra commentary
            4. Many input segments are cut mid-phrase by ASR. Keep the original segmentation. Do not move words or meaning from one marker to another.
            5. If a segment is only a fragment, translate it as a fragment. Do not complete it with words from the next segment.
            6. Do not anticipate or borrow content from a later marker even if that would make the current Chinese line sound smoother.
            7. If a phrase starts in one marker and finishes in the next marker, keep that split. The earlier line may stay incomplete.
            8. Every output line must be non-empty.
            9. Preserve names, titles, identifiers, commands, code symbols, speaker labels, and recurring terms accurately.
            10. Keep tokens unchanged only when they clearly look like code or structured identifiers, such as camelCase, PascalCase used as names, snake_case, ALL_CAPS constants, dotted names, function calls, generic type syntax, code literals, or exact API/package/class names.
            11. Do not keep an English word unchanged merely because it is capitalized at the start of a sentence or segment. Ordinary words should still be translated naturally.
            12. When an identifier appears inside an otherwise natural sentence, translate only the surrounding words and keep the identifier itself unchanged.
            13. If the same identifier appears elsewhere in a clearer original form, keep that canonical identifier form consistent across the batch even if ASR introduces lowercase, spacing, or minor formatting variation.
            14. If you are unsure whether a token is an identifier, prefer natural translation unless it has strong code-like signals such as internal capitalization, digits, underscores, dots, parentheses, or repeated exact identifier use in the batch.
            15. Choose the meaning that best fits the local context. Avoid stiff, overly literal, or dictionary-style wording.
            16. Keep translations consistent within the batch.
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
        var results = new BatchTranslation[batches.size()];
        var completedSegments = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(MAX_CONCURRENT_BATCHES, Thread.ofVirtual().name("translator-", 0).factory())) {
            var futures = new ArrayList<CompletableFuture<BatchTranslation>>(batches.size());

            for (var batch : batches) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return translateBatch(batch, segments.size(), batches.size(), completedSegments);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(e);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, executor));
            }

            var firstFailure = new AtomicReference<Throwable>();
            var futureArray = futures.toArray(new CompletableFuture<?>[0]);
            for (var future : futureArray) {
                future.whenComplete((ignored, throwable) -> {
                    var cause = unwrapFutureFailure(throwable);
                    if (cause == null) {
                        return;
                    }
                    if (firstFailure.compareAndSet(null, cause)) {
                        for (var other : futureArray) {
                            if (other != future) {
                                other.cancel(true);
                            }
                        }
                        executor.shutdownNow();
                    }
                });
            }

            CompletableFuture.allOf(futureArray)
                    .exceptionally(ignored -> null)
                    .join();

            var failure = firstFailure.get();
            if (failure != null) {
                rethrowTaskFailure(failure);
            }

            for (var future : futures) {
                var completed = future.join();
                results[completed.batchIndex()] = completed;
            }
        }

        var translated = new ArrayList<SubtitleSegment>(segments.size());
        for (var translatedBatch : results) {
            if (translatedBatch == null) {
                throw new IOException("并行翻译未返回完整结果。");
            }
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
            AtomicInteger completedSegments
    ) throws IOException, InterruptedException {
        long startedAt = System.nanoTime();
        String batchLabel = "批次 %d/%d".formatted(batch.batchIndex() + 1, totalBatches);
        var trace = new BatchTrace();
        var translated = translateSegments(batch.segments(), batchLabel, trace);
        double seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        int done = completedSegments.addAndGet(batch.segments().size());
        System.out.printf(
                "  -> %s 完成：%d 行（%s），总耗时 %.2fs，tokens %d（输入 %d / 输出 %d），累计 %d/%d%n",
                batchLabel,
                batch.segments().size(),
                trace.formatSummary(),
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
        sb.append("When a phrase continues in the next marker, keep the current line as a fragment instead of completing it early.\n\n");
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

    private TranslationResult translateSegments(List<SubtitleSegment> segments, String label, BatchTrace trace)
            throws IOException, InterruptedException {
        int subtaskIndex = trace.nextSubtaskIndex();
        var prompt = buildPrompt(segments);
        long startedAt = System.nanoTime();
        var response = client.chat(buildSystemPrompt(), prompt);
        trace.record(subtaskIndex, System.nanoTime() - startedAt);

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
                        label + " 子任务 " + subtaskIndex + " 解析失败：" + e.getMessage()
                                + "；返回内容片段：" + previewResponse(response.content()),
                        e
                );
            }

            int mid = segments.size() / 2;
            int leftSize = mid;
            int rightSize = segments.size() - mid;
            System.out.printf(
                    "  -> %s 子任务 %d 输出格式异常，拆分重试：%d -> %d + %d（返回内容片段：%s）%n",
                    label,
                    subtaskIndex,
                    segments.size(),
                    leftSize,
                    rightSize,
                    previewResponse(response.content())
            );

            var left = translateSegments(segments.subList(0, mid), label, trace);
            var right = translateSegments(segments.subList(mid, segments.size()), label, trace);

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

    private void rethrowTaskFailure(Throwable cause) throws IOException, InterruptedException {
        if (cause instanceof InterruptedException e) {
            throw e;
        }
        if (cause instanceof IOException e) {
            throw e;
        }
        if (cause instanceof RuntimeException e) {
            throw e;
        }
        if (cause instanceof Error e) {
            throw e;
        }
        throw new IOException("并行翻译失败", cause);
    }

    private Throwable unwrapFutureFailure(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        while (throwable instanceof CompletionException e && e.getCause() != null) {
            throwable = e.getCause();
        }
        if (throwable instanceof CancellationException) {
            return null;
        }
        return throwable;
    }

    private record BatchRequest(int batchIndex, List<SubtitleSegment> segments) {}

    private record BatchTranslation(int batchIndex, List<SubtitleSegment> segments, List<String> translatedLines) {}

    private record TranslationResult(List<String> lines, int promptTokens, int completionTokens, int totalTokens) {}

    private static final class BatchTrace {
        private final List<SubtaskTiming> timings = new ArrayList<>();
        private int nextSubtaskIndex = 1;

        int nextSubtaskIndex() {
            return nextSubtaskIndex++;
        }

        void record(int subtaskIndex, long elapsedNanos) {
            timings.add(new SubtaskTiming(subtaskIndex, elapsedNanos));
        }

        String formatSummary() {
            var sb = new StringBuilder();
            for (int i = 0; i < timings.size(); i++) {
                var timing = timings.get(i);
                if (i > 0) {
                    sb.append("，");
                }
                sb.append("子任务").append(timing.subtaskIndex())
                        .append(' ')
                        .append("%.2fs".formatted(timing.elapsedNanos() / 1_000_000_000.0));
            }
            return sb.toString();
        }
    }

    private record SubtaskTiming(int subtaskIndex, long elapsedNanos) {}
}
