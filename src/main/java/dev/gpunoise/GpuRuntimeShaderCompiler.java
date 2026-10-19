package dev.gpunoise;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.util.shaderc.Shaderc.*;

/** Compiles the packaged GLSL to SPIR-V at runtime, without a host glslc install. */
final class GpuRuntimeShaderCompiler {
    private GpuRuntimeShaderCompiler() {}

    static ByteBuffer compileResource(String resource, String programName, Map<String, String> definitions,
                                      GpuDensityGraphBuffer.Packed packed, boolean blockProgram) {
        long started = System.nanoTime();
        String source = GpuDensityGraphShaderGenerator.specialize(readResource(resource), packed, blockProgram);
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
            for (Map.Entry<String, String> definition : definitions.entrySet()) {
                shaderc_compile_options_add_macro_definition(options, definition.getKey(), definition.getValue());
            }
            result = shaderc_compile_into_spv(compiler, source, shaderc_glsl_compute_shader,
                    resource, "main", options);
            if (result == 0L) throw new IllegalStateException("shaderc failed to create a compilation result");
            int status = shaderc_result_get_compilation_status(result);
            if (status != shaderc_compilation_status_success) {
                throw new IllegalStateException("GLSL compilation failed for " + programName + ": "
                        + shaderc_result_get_error_message(result));
            }
            ByteBuffer compiled = shaderc_result_get_bytes(result);
            ByteBuffer owned = MemoryUtil.memAlloc(compiled.remaining());
            owned.put(compiled).flip();
            GpuNoise.LOGGER.info("Runtime Vulkan shader compiled: program={}, instructions={}, sourceBytes={}, spirvBytes={}, elapsedMs={}",
                    programName, blockProgram ? packed.blockNodeCount() : packed.nodeCount(),
                    source.length(), owned.remaining(), (System.nanoTime() - started) / 1_000_000L);
            return owned;
        } finally {
            if (result != 0L) shaderc_result_release(result);
            if (options != 0L) shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
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
