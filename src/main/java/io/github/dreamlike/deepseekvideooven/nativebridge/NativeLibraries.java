package io.github.dreamlike.deepseekvideooven.nativebridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class NativeLibraries {

    private static final String NATIVE_RESOURCE_DIR = "native/";
    private static final String NATIVE_LIB_MANIFEST = NATIVE_RESOURCE_DIR + "libs.txt";
    private static final Set<String> OPTIONAL_LIBS = Set.of("ggml-blas", "ggml-metal", "ggml-cuda");

    private static final List<String> WHISPER_LOAD_ORDER = List.of(
            "ggml-base", "ggml-cpu", "ggml-blas", "ggml-metal", "ggml-cuda",
            "ggml", "whisper", "whisper_bridge"
    );
    private static final List<String> HUNYUAN_LOAD_ORDER = List.of(
            "ggml-base", "ggml-cpu", "ggml-blas", "ggml-metal", "ggml-cuda",
            "ggml", "llama", "hunyuan_bridge"
    );

    private static Path extractedDir;
    private static final Set<Path> LOADED = new LinkedHashSet<>();

    private NativeLibraries() {}

    public static synchronized void loadWhisper() {
        load("whisper_bridge", WHISPER_LOAD_ORDER);
    }

    public static synchronized void loadHunyuan() {
        load("hunyuan_bridge", HUNYUAN_LOAD_ORDER);
    }

    private static void load(String requiredLibrary, List<String> loadOrder) {
        try {
            ensureExtracted();
            for (var library : loadOrder) {
                if (OPTIONAL_LIBS.contains(library)) {
                    loadIfPresent(System.mapLibraryName(library));
                } else {
                    loadRequired(System.mapLibraryName(library), requiredLibrary);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract native libs from classpath", e);
        }
    }

    private static void ensureExtracted() throws IOException {
        if (extractedDir != null) {
            return;
        }
        var cl = NativeLibraries.class.getClassLoader();
        extractedDir = Files.createTempDirectory("video-oven-native");
        extractedDir.toFile().deleteOnExit();
        extractNativeLibs(cl, extractedDir, nativeLibNames(cl));
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
                "ggml", "whisper", "whisper_bridge",
                "llama", "hunyuan_bridge");
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

    private static void loadIfPresent(String nativeLib) {
        var lib = extractedDir.resolve(nativeLib);
        if (Files.exists(lib)) {
            loadPath(lib);
        }
    }

    private static void loadRequired(String nativeLib, String requiredLibrary) {
        var lib = extractedDir.resolve(nativeLib);
        if (!Files.exists(lib)) {
            throw new RuntimeException("Native lib not found in classpath: " + NATIVE_RESOURCE_DIR + nativeLib
                    + ". Build with: mvn package"
                    + " (required by " + requiredLibrary + ")");
        }
        loadPath(lib);
    }

    private static void loadPath(Path lib) {
        var absolute = lib.toAbsolutePath();
        if (LOADED.add(absolute)) {
            System.load(absolute.toString());
        }
    }

    private static void validateNativeLibName(String nativeLib) {
        if (nativeLib.contains("/") || nativeLib.contains("\\") || nativeLib.contains("..")) {
            throw new RuntimeException("Invalid native library resource name: " + nativeLib);
        }
    }
}
