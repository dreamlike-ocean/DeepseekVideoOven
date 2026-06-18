package io.github.dreamlike.deepseekvideooven.hunyuan;

import io.github.dreamlike.deepseekvideooven.nativebridge.NativeLibraries;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HunyuanLib implements AutoCloseable {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle LOAD;
    private static final MethodHandle CHAT;
    private static final MethodHandle FREE_RESULT;
    private static final MethodHandle FREE;

    static {
        NativeLibraries.loadHunyuan();
        var lookup = SymbolLookup.loaderLookup();
        try {
            LOAD = LINKER.downcallHandle(
                    lookup.find("video_oven_hunyuan_load").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            CHAT = LINKER.downcallHandle(
                    lookup.find("video_oven_hunyuan_chat").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT)
            );
            FREE_RESULT = LINKER.downcallHandle(
                    lookup.find("video_oven_hunyuan_free_result").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );
            FREE = LINKER.downcallHandle(
                    lookup.find("video_oven_hunyuan_free").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Arena arena;
    private final MemorySegment ctx;

    private HunyuanLib(MemorySegment ctx, Arena arena) {
        this.ctx = ctx;
        this.arena = arena;
    }

    public static HunyuanLib load(Path modelPath, int contextSize, int gpuLayers, int threads) {
        if (!Files.exists(modelPath)) {
            throw new IllegalArgumentException("Hunyuan GGUF model not found: " + modelPath);
        }
        var arena = Arena.ofConfined();
        try {
            var modelPathSeg = arena.allocateFrom(modelPath.toString());
            var ctx = (MemorySegment) LOAD.invokeExact(modelPathSeg, contextSize, gpuLayers, threads);
            if (ctx.equals(MemorySegment.NULL)) {
                arena.close();
                throw new RuntimeException("video_oven_hunyuan_load returned NULL for: " + modelPath);
            }
            return new HunyuanLib(ctx, arena);
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        } catch (Throwable e) {
            arena.close();
            throw new RuntimeException("Failed to load Hunyuan model", e);
        }
    }

    public String chat(
            String systemPrompt,
            String userContent,
            int maxTokens,
            float temperature,
            float topP,
            int topK,
            float repeatPenalty
    ) {
        try (var callArena = Arena.ofConfined()) {
            var systemSeg = callArena.allocateFrom(systemPrompt == null ? "" : systemPrompt);
            var userSeg = callArena.allocateFrom(userContent == null ? "" : userContent);
            var result = (MemorySegment) CHAT.invokeExact(
                    ctx, systemSeg, userSeg,
                    maxTokens, temperature, topP, topK, repeatPenalty
            );
            if (result.equals(MemorySegment.NULL)) {
                throw new RuntimeException("Hunyuan translation failed");
            }
            try {
                return result.reinterpret(Long.MAX_VALUE).getString(0);
            } finally {
                FREE_RESULT.invokeExact(result);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Hunyuan translation failed", e);
        }
    }

    @Override
    public void close() {
        try {
            FREE.invokeExact(ctx);
        } catch (Throwable ignored) {
        }
        arena.close();
    }
}
