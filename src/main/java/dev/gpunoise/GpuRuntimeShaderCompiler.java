package dev.gpunoise;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.util.shaderc.Shaderc.*;

/** Compiles the packaged GLSL to SPIR-V at runtime, without a host glslc install. */
final class GpuRuntimeShaderCompiler {
    private static final int MAX_CACHED_VARIANTS = 12;
    private static final long MAX_CACHED_SPIRV_BYTES = 12L * 1024L * 1024L;
    private static final LinkedHashMap<String, byte[]> SPIRV_CACHE = new LinkedHashMap<>(16, .75f, true);
    private static long cachedSpirvBytes;

    private GpuRuntimeShaderCompiler() {}

    static ByteBuffer compileResource(String resource, String programName, Map<String, String> definitions,
                                      GpuDensityGraph graph, GpuDensityGraphBuffer.Packed packed,
                                      boolean blockProgram) {
        long started = System.nanoTime();
        String source = GpuDensityGraphShaderGenerator.specialize(readResource(resource), packed, blockProgram);
        TreeMap<String, String> sortedDefinitions = new TreeMap<>(definitions);
        String cacheKey = cacheKey(source, sortedDefinitions);
        byte[] cached = cached(cacheKey);
        List<String> customTypes = graph.customDensityFunctionTypes();
        if (cached != null) {
            GpuNoise.LOGGER.info("Runtime Vulkan shader cache hit: program={}, instructions={}, customDensityTypes={}, spirvBytes={}, elapsedMs={}",
                    programName, blockProgram ? packed.blockNodeCount() : packed.nodeCount(),
                    customTypes, cached.length, (System.nanoTime() - started) / 1_000_000L);
            return MemoryUtil.memAlloc(cached.length).put(cached).flip();
        }
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0L) throw new IllegalStateException("shaderc could not create a compiler");
        long options = 0L;
        long result = 0L;
        try {
            options = shaderc_compile_options_initialize();
            if (options == 0L) throw new IllegalStateException("shaderc could not create compile options");
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan,
                    shaderc_env_version_vulkan_1_1);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            for (Map.Entry<String, String> definition : sortedDefinitions.entrySet()) {
                shaderc_compile_options_add_macro_definition(options, definition.getKey(), definition.getValue());
            }
            result = shaderc_compile_into_spv(compiler, source, shaderc_glsl_compute_shader,
                    resource, "main", options);
            if (result == 0L) throw new IllegalStateException("shaderc failed to create a compilation result");
            int status = shaderc_result_get_compilation_status(result);
            if (status != shaderc_compilation_status_success) {
                throw new IllegalStateException("GLSL compilation failed for " + programName
                        + " (custom density types: " + customTypes + "): "
                        + shaderc_result_get_error_message(result));
            }
            ByteBuffer compiled = shaderc_result_get_bytes(result);
            ByteBuffer owned = MemoryUtil.memAlloc(compiled.remaining());
            owned.put(compiled).flip();
            byte[] spirv = new byte[owned.remaining()];
            owned.duplicate().get(spirv);
            cache(cacheKey, spirv);
            GpuNoise.LOGGER.info("Runtime Vulkan shader compiled: program={}, instructions={}, customDensityTypes={}, sourceBytes={}, spirvBytes={}, elapsedMs={}",
                    programName, blockProgram ? packed.blockNodeCount() : packed.nodeCount(), customTypes,
                    source.length(), owned.remaining(), (System.nanoTime() - started) / 1_000_000L);
            return owned;
        } finally {
            if (result != 0L) shaderc_result_release(result);
            if (options != 0L) shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    private static String cacheKey(String source, Map<String, String> definitions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(source.getBytes(StandardCharsets.UTF_8));
            definitions.forEach((key, value) -> {
                digest.update((byte) 0);
                digest.update(key.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(value.getBytes(StandardCharsets.UTF_8));
            });
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) hex.append(Character.forDigit((value >>> 4) & 0xf, 16))
                    .append(Character.forDigit(value & 0xf, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static synchronized byte[] cached(String key) {
        return SPIRV_CACHE.get(key);
    }

    private static synchronized void cache(String key, byte[] spirv) {
        if (spirv.length > MAX_CACHED_SPIRV_BYTES) return;
        byte[] previous = SPIRV_CACHE.put(key, spirv);
        if (previous != null) cachedSpirvBytes -= previous.length;
        cachedSpirvBytes += spirv.length;
        while (SPIRV_CACHE.size() > MAX_CACHED_VARIANTS || cachedSpirvBytes > MAX_CACHED_SPIRV_BYTES) {
            Iterator<Map.Entry<String, byte[]>> entries = SPIRV_CACHE.entrySet().iterator();
            Map.Entry<String, byte[]> eldest = entries.next();
            cachedSpirvBytes -= eldest.getValue().length;
            entries.remove();
        }
    }

    private static String readResource(String resource) {
        try (var input = GpuRuntimeShaderCompiler.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing GLSL source " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("could not read GLSL source " + resource, failure);
        }
    }
}
