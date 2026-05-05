package io.github.dreamlike.deepseekvideooven.ffmpeg;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class FFmpegBridge {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle EXTRACT_AUDIO;
    private static final MethodHandle BURN_SUBTITLES;

    static {
        findAndLoad();
        var lookup = SymbolLookup.loaderLookup();
        try {
            EXTRACT_AUDIO = LINKER.downcallHandle(
                    lookup.find("ffmpeg_extract_audio").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            BURN_SUBTITLES = LINKER.downcallHandle(
                    lookup.find("ffmpeg_burn_subtitles").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FFmpegBridge() {}

    private static void findAndLoad() {
        var os = System.getProperty("os.name").toLowerCase();
        var libName = os.contains("mac") ? "libffmpeg_bridge.dylib"
                    : os.contains("win") ? "ffmpeg_bridge.dll"
                    : "libffmpeg_bridge.so";
        var searchPaths = List.of(
                Path.of("target", "native-libs", libName),
                Path.of(libName)
        );
        for (var p : searchPaths) {
            if (Files.exists(p)) {
                System.load(p.toAbsolutePath().toString());
                return;
            }
        }
        throw new RuntimeException("Cannot find " + libName);
    }

    public static float[] extractAudio(Path videoPath) {
        try (var arena = Arena.ofConfined()) {
            var inputSeg = arena.allocateFrom(videoPath.toString());
            var samplesPtrSeg = arena.allocate(ValueLayout.ADDRESS);
            var lenSeg = arena.allocate(ValueLayout.JAVA_INT);

            int ret = (int) EXTRACT_AUDIO.invokeExact(inputSeg, samplesPtrSeg, lenSeg);
            if (ret != 0) {
                throw new RuntimeException("ffmpeg_extract_audio failed: " + ret);
            }

            var samplesPtr = samplesPtrSeg.get(ValueLayout.ADDRESS, 0);
            int len = lenSeg.get(ValueLayout.JAVA_INT, 0);

            if (len <= 0 || samplesPtr.equals(MemorySegment.NULL)) {
                throw new RuntimeException("No audio extracted");
            }

            var samplesSeg = samplesPtr.reinterpret(len * 4L);
            return samplesSeg.toArray(ValueLayout.JAVA_FLOAT);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Audio extraction failed", e);
        }
    }

    public static void burnSubtitles(Path videoPath, Path assPath, Path outputPath) {
        try (var arena = Arena.ofConfined()) {
            var inputSeg = arena.allocateFrom(videoPath.toString());
            var assSeg = arena.allocateFrom(assPath.toString());
            var outputSeg = arena.allocateFrom(outputPath.toString());

            int ret = (int) BURN_SUBTITLES.invokeExact(inputSeg, assSeg, outputSeg);
            if (ret != 0) {
                throw new RuntimeException("ffmpeg_burn_subtitles failed: " + ret);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Subtitle burning failed", e);
        }
    }
}
