package io.github.dreamlike.deepseekvideooven.pipeline;

import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SubtitleGenerator {

    private SubtitleGenerator() {}

    public static Path generate(List<SubtitleSegment> segments, Path outputPath) throws IOException {
        System.out.println("[4/5] 生成 ASS 字幕...");

        var sb = new StringBuilder();
        sb.append("""
                [Script Info]
                ScriptType: v4.00+
                PlayResX: 1920
                PlayResY: 1080
                WrapStyle: 2
                ScaledBorderAndShadow: yes

                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Default,Arial,28,&H00FFFFFF,&H0000FFFF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,2.5,0,2,20,20,20,1

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                """);

        for (int i = 0; i < segments.size(); i++) {
            var seg = segments.get(i);
            sb.append(seg.toAssEvent(i, 1920, 1080)).append('\n');
        }

        Files.writeString(outputPath, sb.toString());
        System.out.printf("  -> 已写出 %d 条字幕到 %s%n", segments.size(), outputPath);
        return outputPath;
    }
}
