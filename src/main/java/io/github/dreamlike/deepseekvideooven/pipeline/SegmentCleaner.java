package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;

import java.util.ArrayList;
import java.util.List;

final class SegmentCleaner {

    private static final long MIN_GAP_MS = 50;
    private static final long MIN_DURATION_MS = 300;

    private SegmentCleaner() {}

    static List<SubtitleSegment> clean(List<SubtitleSegment> segments) {
        if (segments.isEmpty()) return segments;

        var cleaned = new ArrayList<SubtitleSegment>();
        cleaned.add(segments.getFirst());

        for (int i = 1; i < segments.size(); i++) {
            var prev = cleaned.getLast();
            var curr = segments.get(i);

            long gap = curr.t0Ms() - prev.t1Ms();
            long prevDuration = prev.t1Ms() - prev.t0Ms();
            long currDuration = curr.t1Ms() - curr.t0Ms();

            String prevText = prev.text();
            String currText = curr.text();

            if (prevText.length() > currText.length() && prevText.endsWith(currText)) {
                continue;
            }

            String merged = mergeDedup(prevText, currText);
            if (!merged.equals(prevText + currText)) {
                cleaned.set(cleaned.size() - 1,
                        new SubtitleSegment(prev.t0Ms(), curr.t1Ms(), merged));
                continue;
            }

            if (gap < MIN_GAP_MS || prevDuration < MIN_DURATION_MS || currDuration < MIN_DURATION_MS) {
                cleaned.set(cleaned.size() - 1,
                        new SubtitleSegment(prev.t0Ms(), Math.max(prev.t1Ms(), curr.t1Ms()),
                                prevText + " " + currText));
                continue;
            }

            cleaned.add(curr);
        }

        System.out.printf("  -> Cleaned segments: %d -> %d%n", segments.size(), cleaned.size());
        return cleaned;
    }

    private static String mergeDedup(String prev, String curr) {
        for (int len = Math.min(prev.length(), curr.length()); len >= 3; len--) {
            if (prev.endsWith(curr.substring(0, len))) {
                return prev + curr.substring(len);
            }
        }
        return prev + curr;
    }
}
