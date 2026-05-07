package io.github.dreamlike.deepseekvideooven.whisper;

import io.github.dreamlike.deepseekvideooven.model.SubtitleSegment;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class WhisperLib implements AutoCloseable {

    private static final String NATIVE_RESOURCE_DIR = "native/";
    private static final String NATIVE_LIB_MANIFEST = NATIVE_RESOURCE_DIR + "libs.txt";

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle INIT_FROM_FILE;
    private static final MethodHandle TRANSCRIBE;
    private static final MethodHandle N_SEGMENTS;
    private static final MethodHandle GET_T0;
    private static final MethodHandle GET_T1;
    private static final MethodHandle GET_TEXT;
    private static final MethodHandle FREE;

    static {
        findAndLoadBridge();
        var lookup = SymbolLookup.loaderLookup();
        try {
            INIT_FROM_FILE = LINKER.downcallHandle(
                    lookup.find("whisper_init_from_file").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            TRANSCRIBE = LINKER.downcallHandle(
                    lookup.find("whisper_bridge_transcribe").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS)
            );
            N_SEGMENTS = LINKER.downcallHandle(
                    lookup.find("whisper_full_n_segments").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            );
            GET_T0 = LINKER.downcallHandle(
                    lookup.find("whisper_full_get_segment_t0").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
            GET_T1 = LINKER.downcallHandle(
                    lookup.find("whisper_full_get_segment_t1").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
            GET_TEXT = LINKER.downcallHandle(
                    lookup.find("whisper_full_get_segment_text").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
            FREE = LINKER.downcallHandle(
                    lookup.find("whisper_free").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Arena arena;
    private final MemorySegment ctx;

    private WhisperLib(MemorySegment ctx, Arena arena) {
        this.ctx = ctx;
        this.arena = arena;
    }

    public static WhisperLib load(Path modelPath) {
        if (!Files.exists(modelPath)) {
            throw new IllegalArgumentException("Whisper model not found: " + modelPath);
        }

        var arena = Arena.ofConfined();
        try {
            var modelPathSeg = arena.allocateFrom(modelPath.toString());
            var ctx = (MemorySegment) INIT_FROM_FILE.invokeExact(modelPathSeg);

            if (ctx.equals(MemorySegment.NULL)) {
                arena.close();
                throw new RuntimeException("whisper_init_from_file returned NULL for: " + modelPath);
            }

            return new WhisperLib(ctx, arena);
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        } catch (Throwable e) {
            arena.close();
            throw new RuntimeException("Failed to load whisper model", e);
        }
    }

    public List<SubtitleSegment> transcribe(float[] audio, String language) {
        try {
            var samplesSeg = arena.allocateFrom(ValueLayout.JAVA_FLOAT, audio);
            var langSeg = language != null ? arena.allocateFrom(language) : arena.allocateFrom("auto");

            int result = (int) TRANSCRIBE.invokeExact(ctx, samplesSeg, audio.length,
                    Runtime.getRuntime().availableProcessors(), langSeg);
            if (result != 0) {
                throw new RuntimeException("whisper_bridge_transcribe failed with code: " + result);
            }

            int n = (int) N_SEGMENTS.invokeExact(ctx);
            var segments = new ArrayList<SubtitleSegment>(n);

            for (int i = 0; i < n; i++) {
                long t0 = (long) GET_T0.invokeExact(ctx, i) * 10;
                long t1 = (long) GET_T1.invokeExact(ctx, i) * 10;
                var textSeg = (MemorySegment) GET_TEXT.invokeExact(ctx, i);
                var text = textSeg.reinterpret(Long.MAX_VALUE).getString(0);

                if (!text.isBlank()) {
                    segments.add(new SubtitleSegment(t0, t1, text.trim()));
                }
            }

            return segments;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("whisper transcription failed", e);
        }
    }

    public List<SubtitleSegment> transcribe(float[] audio) {
        return transcribe(audio, null);
    }

    @Override
    public void close() {
        try {
            FREE.invokeExact(ctx);
        } catch (Throwable ignored) {
        }
        arena.close();
    }

    private static void findAndLoadBridge() {
        var cl = WhisperLib.class.getClassLoader();
        try {
            var tmpDir = Files.createTempDirectory("video-oven-native");
            tmpDir.toFile().deleteOnExit();

            extractNativeLibs(cl, tmpDir, nativeLibNames(cl));
            loadNativeLibraries(tmpDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract native libs from classpath", e);
        }
    }

    private static List<String> nativeLibNames(ClassLoader cl) throws IOException {
        var nativeLibs = new LinkedHashSet<String>();

        try (var manifest = cl.getResourceAsStream(NATIVE_LIB_MANIFEST)) {
            if (manifest != null) {
                var content = new String(manifest.readAllBytes(), StandardCharsets.UTF_8);
                content.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .forEach(nativeLibs::add);
            }
        }

        addMappedLibraryNames(nativeLibs,
                "ggml-base", "ggml-cpu", "ggml-blas", "ggml-metal", "ggml-cuda",
                "ggml", "whisper", "whisper_bridge");
        return List.copyOf(nativeLibs);
    }

    private static void addMappedLibraryNames(LinkedHashSet<String> nativeLibs, String... libraryNames) {
        for (var libraryName : libraryNames) {
            nativeLibs.add(System.mapLibraryName(libraryName));
        }
    }

    private static void extractNativeLibs(ClassLoader cl, Path tmpDir, List<String> nativeLibs) throws IOException {
        for (var nativeLib : nativeLibs) {
            validateNativeLibName(nativeLib);
            try (var in = cl.getResourceAsStream(NATIVE_RESOURCE_DIR + nativeLib)) {
                if (in == null) {
                    continue;
                }
                var extracted = tmpDir.resolve(nativeLib);
                Files.copy(in, extracted, StandardCopyOption.REPLACE_EXISTING);
                extracted.toFile().deleteOnExit();
            }
        }
    }

    private static void validateNativeLibName(String nativeLib) {
        if (nativeLib.contains("/") || nativeLib.contains("\\") || nativeLib.contains("..")) {
            throw new RuntimeException("Invalid native library resource name: " + nativeLib);
        }
    }

    private static void loadNativeLibraries(Path tmpDir) {
        loadRequired(tmpDir, System.mapLibraryName("ggml-base"));
        loadRequired(tmpDir, System.mapLibraryName("ggml-cpu"));
        loadIfPresent(tmpDir, System.mapLibraryName("ggml-blas"));
        loadIfPresent(tmpDir, System.mapLibraryName("ggml-metal"));
        loadIfPresent(tmpDir, System.mapLibraryName("ggml-cuda"));
        loadRequired(tmpDir, System.mapLibraryName("ggml"));
        loadRequired(tmpDir, System.mapLibraryName("whisper"));
        loadRequired(tmpDir, System.mapLibraryName("whisper_bridge"));
    }

    private static void loadIfPresent(Path tmpDir, String nativeLib) {
        var lib = tmpDir.resolve(nativeLib);
        if (Files.exists(lib)) {
            System.load(lib.toAbsolutePath().toString());
        }
    }

    private static void loadRequired(Path tmpDir, String nativeLib) {
        var lib = tmpDir.resolve(nativeLib);
        if (!Files.exists(lib)) {
            throw new RuntimeException("Native lib not found in classpath: " + NATIVE_RESOURCE_DIR + nativeLib
                    + ". Build with: mvn package");
        }
        System.load(lib.toAbsolutePath().toString());
    }
}
