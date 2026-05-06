package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;

import java.util.ArrayList;
import java.util.List;

final class SegmentCleaner {

    private static final long MAX_GAP_MS = 300;
    private static final long SUBTITLE_MAX_MERGED_DURATION_MS = 12_000;
    private static final int SUBTITLE_MAX_MERGED_VISIBLE_CHARS = 60;
    private static final long SUBTITLE_SPLIT_TARGET_DURATION_MS = 8_000;
    private static final int SUBTITLE_SPLIT_TARGET_VISIBLE_CHARS = 36;
    private static final long SUBTITLE_MIN_SPLIT_DURATION_MS = 1_200;
    private static final int SUBTITLE_MIN_SPLIT_VISIBLE_CHARS = 8;
    private static final long TRANSCRIPT_MAX_MERGED_DURATION_MS = 6_500;
    private static final int TRANSCRIPT_MAX_MERGED_VISIBLE_CHARS = 42;
    private static final long TRANSCRIPT_SPLIT_TARGET_DURATION_MS = 6_500;
    private static final int TRANSCRIPT_SPLIT_TARGET_VISIBLE_CHARS = 42;
    private static final long SHORT_DURATION_MS = 1800;
    private static final int SHORT_VISIBLE_CHARS = 8;
    private static final int MIN_DEDUP_OVERLAP = 3;
    private static final String STRONG_TERMINATORS = "。！？!?";
    private static final String SECONDARY_BREAKS = "，、；：,;:";
    private static final String TRAILING_CLOSERS = "\"'”’)]}）】》」』";
    private static final String SUBTITLE_CLEAN_LABEL = "外挂字幕分段整理";
    private static final String TRANSCRIPT_CLEAN_LABEL = "文稿断句整理";
    private static final CleaningProfile SUBTITLE_PROFILE = new CleaningProfile(
            false,
            true,
            true,
            SUBTITLE_CLEAN_LABEL,
            SUBTITLE_MAX_MERGED_DURATION_MS,
            SUBTITLE_MAX_MERGED_VISIBLE_CHARS,
            SUBTITLE_SPLIT_TARGET_DURATION_MS,
            SUBTITLE_SPLIT_TARGET_VISIBLE_CHARS,
            SUBTITLE_MIN_SPLIT_DURATION_MS,
            SUBTITLE_MIN_SPLIT_VISIBLE_CHARS,
            false
    );
    private static final CleaningProfile TRANSCRIPT_PROFILE = new CleaningProfile(
            true,
            false,
            false,
            TRANSCRIPT_CLEAN_LABEL,
            TRANSCRIPT_MAX_MERGED_DURATION_MS,
            TRANSCRIPT_MAX_MERGED_VISIBLE_CHARS,
            TRANSCRIPT_SPLIT_TARGET_DURATION_MS,
            TRANSCRIPT_SPLIT_TARGET_VISIBLE_CHARS,
            0,
            0,
            true
    );

    private SegmentCleaner() {}

    static List<SubtitleSegment> clean(List<SubtitleSegment> segments) {
        return cleanInternal(segments, SUBTITLE_PROFILE);
    }

    static List<SubtitleSegment> cleanForTranscript(List<SubtitleSegment> segments) {
        return cleanInternal(segments, TRANSCRIPT_PROFILE);
    }

    private static List<SubtitleSegment> cleanInternal(List<SubtitleSegment> segments, CleaningProfile profile) {
        if (segments.isEmpty()) return segments;

        var normalized = normalizeSegments(segments);
        if (normalized.isEmpty()) return List.of();

        var merged = new ArrayList<WorkingSegment>();
        merged.add(normalized.getFirst());

        for (int i = 1; i < normalized.size(); i++) {
            var prev = merged.getLast();
            var curr = normalized.get(i);
            var merge = mergeTexts(prev.text(), curr.text());

            if (shouldMerge(prev, curr, merge, profile)) {
                merged.set(merged.size() - 1, new WorkingSegment(
                        prev.t0Ms(),
                        Math.max(prev.t1Ms(), curr.t1Ms()),
                        merge.text(),
                        curr.sourceId()
                ));
                continue;
            }

            merged.add(curr);
        }

        var split = splitSegments(merged, profile);
        var output = split.stream()
                .map(WorkingSegment::toSubtitle)
                .toList();
        System.out.printf("  -> %s：%d -> %d%n", profile.label(), segments.size(), output.size());
        return output;
    }

    private static List<WorkingSegment> normalizeSegments(List<SubtitleSegment> segments) {
        var normalized = new ArrayList<WorkingSegment>();
        for (int i = 0; i < segments.size(); i++) {
            var segment = segments.get(i);
            var text = segment.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            normalized.add(new WorkingSegment(segment.t0Ms(), segment.t1Ms(), text, i));
        }
        return normalized;
    }

    private static List<WorkingSegment> splitSegments(List<WorkingSegment> segments, CleaningProfile profile) {
        var split = new ArrayList<WorkingSegment>();
        for (var segment : segments) {
            split.addAll(splitSegment(segment, profile));
        }
        return split;
    }

    private static List<WorkingSegment> splitSegment(WorkingSegment segment, CleaningProfile profile) {
        var text = segment.text();
        if (!profile.allowIntraSegmentSplit()) {
            return List.of(segment);
        }

        if (!shouldSplitSegment(segment.t0Ms(), segment.t1Ms(), text, profile)) {
            return List.of(segment);
        }

        List<String> parts;
        if (profile.splitOnSecondaryPunctuation()) {
            parts = splitByPreferredPunctuation(text, profile, true);
        } else {
            parts = splitText(text);
            if (parts.size() > 1) {
                parts = groupSentenceParts(parts, profile.splitTargetVisibleChars());
            }
        }

        if (parts.size() == 1) {
            return List.of(segment);
        }

        long durationMs = Math.max(0, segment.t1Ms() - segment.t0Ms());
        parts = mergeTinySplitParts(parts, durationMs, profile);
        if (parts.size() == 1) {
            return List.of(segment);
        }

        int totalWeight = parts.stream()
                .mapToInt(SegmentCleaner::segmentWeight)
                .sum();

        var split = new ArrayList<WorkingSegment>(parts.size());
        long cursor = segment.t0Ms();
        int consumedWeight = 0;

        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            if (i == parts.size() - 1) {
                split.add(new WorkingSegment(cursor, segment.t1Ms(), part, segment.sourceId()));
                continue;
            }

            consumedWeight += segmentWeight(part);
            long next = segment.t0Ms() + Math.round((double) durationMs * consumedWeight / totalWeight);
            next = Math.max(cursor, Math.min(next, segment.t1Ms()));
            split.add(new WorkingSegment(cursor, next, part, segment.sourceId()));
            cursor = next;
        }

        return split;
    }

    private static List<String> splitByPreferredPunctuation(String text, CleaningProfile profile, boolean allowSecondary) {
        var candidates = collectCutCandidates(text, allowSecondary);
        if (candidates.isEmpty()) {
            return List.of(text);
        }

        var parts = new ArrayList<String>();
        int start = 0;

        while (start < text.length()) {
            start = skipWhitespace(text, start);
            if (start >= text.length()) {
                break;
            }

            var remaining = text.substring(start).trim();
            if (remaining.isEmpty()) {
                break;
            }
            if (visibleChars(remaining) <= profile.splitTargetVisibleChars()) {
                parts.add(remaining);
                break;
            }

            int cut = chooseCut(text, start, candidates, profile, allowSecondary);
            if (cut < 0 || cut <= start) {
                parts.add(remaining);
                break;
            }

            var part = text.substring(start, cut).trim();
            if (part.isEmpty()) {
                parts.add(remaining);
                break;
            }

            parts.add(part);
            start = cut;
        }

        return parts.isEmpty() ? List.of(text) : parts;
    }

    private static List<CutCandidate> collectCutCandidates(String text, boolean allowSecondary) {
        var candidates = new ArrayList<CutCandidate>();
        int i = 0;

        while (i < text.length()) {
            int cutEnd = terminatorEnd(text, i);
            if (cutEnd >= 0) {
                candidates.add(new CutCandidate(includeTrailingClosers(text, cutEnd), true));
                i = cutEnd;
                continue;
            }

            if (allowSecondary) {
                cutEnd = secondaryBreakEnd(text, i);
                if (cutEnd >= 0) {
                    candidates.add(new CutCandidate(includeTrailingClosers(text, cutEnd), false));
                    i = cutEnd;
                    continue;
                }
            }

            i++;
        }

        return candidates;
    }

    private static int chooseCut(String text, int start, List<CutCandidate> candidates, CleaningProfile profile, boolean allowSecondary) {
        int targetVisibleChars = profile.splitTargetVisibleChars();
        int minVisibleChars = profile.minSplitVisibleChars() > 0
                ? profile.minSplitVisibleChars()
                : 0;
        int strongBefore = -1;
        int secondaryBefore = -1;
        int shortStrongBefore = -1;
        int shortSecondaryBefore = -1;
        int firstStrongAfter = -1;
        int firstSecondaryAfter = -1;

        for (var candidate : candidates) {
            if (candidate.end() <= start) {
                continue;
            }

            var part = text.substring(start, candidate.end()).trim();
            if (part.isEmpty()) {
                continue;
            }

            int visibleChars = visibleChars(part);
            if (visibleChars <= targetVisibleChars) {
                if (candidate.strong()) {
                    if (visibleChars >= minVisibleChars) {
                        strongBefore = candidate.end();
                    } else {
                        shortStrongBefore = candidate.end();
                    }
                } else {
                    if (visibleChars >= minVisibleChars) {
                        secondaryBefore = candidate.end();
                    } else {
                        shortSecondaryBefore = candidate.end();
                    }
                }
                continue;
            }

            if (candidate.strong()) {
                if (firstStrongAfter < 0) {
                    firstStrongAfter = candidate.end();
                }
            } else if (allowSecondary && firstSecondaryAfter < 0) {
                firstSecondaryAfter = candidate.end();
            }
        }

        if (strongBefore >= 0) {
            return strongBefore;
        }
        if (allowSecondary && secondaryBefore >= 0) {
            return secondaryBefore;
        }
        if (firstStrongAfter >= 0) {
            return firstStrongAfter;
        }
        if (allowSecondary) {
            if (firstSecondaryAfter >= 0) {
                return firstSecondaryAfter;
            }
            if (shortSecondaryBefore >= 0) {
                return shortSecondaryBefore;
            }
        }
        if (shortStrongBefore >= 0) {
            return shortStrongBefore;
        }
        return -1;
    }

    private static List<String> mergeTinySplitParts(List<String> parts, long durationMs, CleaningProfile profile) {
        if (parts.size() <= 1 || (profile.minSplitDurationMs() <= 0 && profile.minSplitVisibleChars() <= 0)) {
            return parts;
        }

        var merged = new ArrayList<>(parts);
        boolean changed;
        do {
            changed = false;
            int totalWeight = merged.stream()
                    .mapToInt(SegmentCleaner::segmentWeight)
                    .sum();

            for (int i = 0; i < merged.size(); i++) {
                if (!isTinySplitPart(merged.get(i), durationMs, totalWeight, profile)) {
                    continue;
                }
                if (merged.size() == 1) {
                    return merged;
                }

                if (i == merged.size() - 1) {
                    merged.set(i - 1, joinWithSpacing(merged.get(i - 1), merged.get(i)));
                    merged.remove(i);
                } else {
                    merged.set(i, joinWithSpacing(merged.get(i), merged.get(i + 1)));
                    merged.remove(i + 1);
                }
                changed = true;
                break;
            }
        } while (changed);

        return merged;
    }

    private static boolean isTinySplitPart(String text, long durationMs, int totalWeight, CleaningProfile profile) {
        if (totalWeight <= 0) {
            return false;
        }

        if (profile.minSplitVisibleChars() > 0 && visibleChars(text) < profile.minSplitVisibleChars()) {
            return true;
        }

        if (profile.minSplitDurationMs() > 0) {
            long estimatedDurationMs = Math.round((double) durationMs * segmentWeight(text) / totalWeight);
            return estimatedDurationMs < profile.minSplitDurationMs();
        }

        return false;
    }

    private static List<String> splitText(String text) {
        var parts = new ArrayList<String>();
        int partStart = 0;
        int i = 0;

        while (i < text.length()) {
            int terminatorEnd = terminatorEnd(text, i);
            if (terminatorEnd < 0) {
                i++;
                continue;
            }

            int splitEnd = terminatorEnd;
            while (splitEnd < text.length() && isTrailingCloser(text.charAt(splitEnd))) {
                splitEnd++;
            }

            int next = skipWhitespace(text, splitEnd);
            if (next < text.length() && hasVisibleText(text, next)) {
                var part = text.substring(partStart, splitEnd).trim();
                if (!part.isEmpty()) {
                    parts.add(part);
                }
                partStart = next;
                i = next;
                continue;
            }

            i = splitEnd;
        }

        var tail = text.substring(partStart).trim();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }

        return parts.isEmpty() ? List.of(text) : parts;
    }

    private static boolean shouldSplitSegment(long t0Ms, long t1Ms, String text, CleaningProfile profile) {
        long durationMs = Math.max(0, t1Ms - t0Ms);
        return durationMs > profile.splitTargetDurationMs() || visibleChars(text) > profile.splitTargetVisibleChars();
    }

    private static List<String> groupSentenceParts(List<String> parts, int maxVisibleChars) {
        var grouped = new ArrayList<String>();
        var current = new StringBuilder();
        int currentVisibleChars = 0;

        for (var part : parts) {
            int partVisibleChars = visibleChars(part);
            if (currentVisibleChars > 0 && currentVisibleChars + partVisibleChars > maxVisibleChars) {
                grouped.add(current.toString());
                current.setLength(0);
                currentVisibleChars = 0;
            }

            current.append(part);
            currentVisibleChars += partVisibleChars;
        }

        if (!current.isEmpty()) {
            grouped.add(current.toString());
        }

        return grouped;
    }

    private static boolean shouldMerge(WorkingSegment prev, WorkingSegment curr, MergeResult merge, CleaningProfile profile) {
        long gap = curr.t0Ms() - prev.t1Ms();
        if (gap > MAX_GAP_MS) {
            return false;
        }

        boolean prevEnded = endsWithStrongTerminator(prev.text())
                || (profile.secondaryBoundaryStopsMerge() && endsWithSecondaryBreak(prev.text()));
        boolean shouldTry = merge.deduped()
                || !prevEnded
                || (profile.mergeShortCompletedSegments() && (isShort(prev) || isShort(curr)));
        if (!shouldTry) {
            return false;
        }

        long mergedDuration = Math.max(prev.t1Ms(), curr.t1Ms()) - prev.t0Ms();
        if (mergedDuration > profile.maxMergedDurationMs()) {
            return false;
        }

        return visibleChars(merge.text()) <= profile.maxMergedVisibleChars();
    }

    private static MergeResult mergeTexts(String prev, String curr) {
        var left = prev.stripTrailing();
        var right = curr.stripLeading();

        if (left.isEmpty()) return new MergeResult(right, false);
        if (right.isEmpty()) return new MergeResult(left, false);

        if (left.length() > right.length() && left.endsWith(right)) {
            return new MergeResult(left, true);
        }

        int overlap = overlapLength(left, right);
        if (overlap >= MIN_DEDUP_OVERLAP) {
            return new MergeResult(left + right.substring(overlap), true);
        }

        return new MergeResult(joinWithSpacing(left, right), false);
    }

    private static int overlapLength(String left, String right) {
        int max = Math.min(left.length(), right.length());
        for (int len = max; len >= MIN_DEDUP_OVERLAP; len--) {
            if (left.endsWith(right.substring(0, len))) {
                return len;
            }
        }
        return 0;
    }

    private static String joinWithSpacing(String left, String right) {
        if (needsSpaceBetween(left, right)) {
            return left + " " + right;
        }
        return left + right;
    }

    private static boolean needsSpaceBetween(String left, String right) {
        return isAsciiWordChar(left.charAt(left.length() - 1))
                && isAsciiWordChar(right.charAt(0));
    }

    private static boolean isShort(WorkingSegment segment) {
        long duration = segment.t1Ms() - segment.t0Ms();
        return duration < SHORT_DURATION_MS || visibleChars(segment.text()) < SHORT_VISIBLE_CHARS;
    }

    private static int secondaryBreakEnd(String text, int index) {
        char ch = text.charAt(index);
        if (SECONDARY_BREAKS.indexOf(ch) < 0) {
            return -1;
        }

        int end = index + 1;
        while (end < text.length() && SECONDARY_BREAKS.indexOf(text.charAt(end)) >= 0) {
            end++;
        }
        return end;
    }

    private static int terminatorEnd(String text, int index) {
        char ch = text.charAt(index);
        if (STRONG_TERMINATORS.indexOf(ch) >= 0) {
            int end = index + 1;
            while (end < text.length() && STRONG_TERMINATORS.indexOf(text.charAt(end)) >= 0) {
                end++;
            }
            return end;
        }

        if (ch == '…') {
            int end = index + 1;
            while (end < text.length() && text.charAt(end) == '…') {
                end++;
            }
            return end;
        }

        if (ch == '.') {
            int end = index + 1;
            while (end < text.length() && text.charAt(end) == '.') {
                end++;
            }
            if (end - index >= 3) {
                return end;
            }
        }

        return -1;
    }

    private static int includeTrailingClosers(String text, int index) {
        int end = index;
        while (end < text.length() && isTrailingCloser(text.charAt(end))) {
            end++;
        }
        return end;
    }

    private static boolean endsWithStrongTerminator(String text) {
        int i = text.length() - 1;
        while (i >= 0) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || isTrailingCloser(ch)) {
                i--;
                continue;
            }

            if (STRONG_TERMINATORS.indexOf(ch) >= 0 || ch == '…') {
                return true;
            }

            if (ch == '.') {
                int dots = 0;
                while (i >= 0 && text.charAt(i) == '.') {
                    dots++;
                    i--;
                }
                return dots >= 3;
            }

            return false;
        }
        return false;
    }

    private static boolean endsWithSecondaryBreak(String text) {
        int i = text.length() - 1;
        while (i >= 0) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || isTrailingCloser(ch)) {
                i--;
                continue;
            }
            return SECONDARY_BREAKS.indexOf(ch) >= 0;
        }
        return false;
    }

    private static int skipWhitespace(String text, int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean hasVisibleText(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static int segmentWeight(String text) {
        return Math.max(visibleChars(text), 1);
    }

    private static int visibleChars(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isTrailingCloser(char ch) {
        return TRAILING_CLOSERS.indexOf(ch) >= 0;
    }

    private static boolean isAsciiWordChar(char ch) {
        return ch < 128 && Character.isLetterOrDigit(ch);
    }

    private record CleaningProfile(
            boolean allowIntraSegmentSplit,
            boolean splitOnSecondaryPunctuation,
            boolean secondaryBoundaryStopsMerge,
            String label,
            long maxMergedDurationMs,
            int maxMergedVisibleChars,
            long splitTargetDurationMs,
            int splitTargetVisibleChars,
            long minSplitDurationMs,
            int minSplitVisibleChars,
            boolean mergeShortCompletedSegments
    ) {}

    private record CutCandidate(int end, boolean strong) {}

    private record MergeResult(String text, boolean deduped) {}

    private record WorkingSegment(long t0Ms, long t1Ms, String text, int sourceId) {
        SubtitleSegment toSubtitle() {
            return new SubtitleSegment(t0Ms, t1Ms, text);
        }
    }
}
