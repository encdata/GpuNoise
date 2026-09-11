# GPU Noise benchmark sequence

1. Use `NoiseMath` and `noise_benchmark.comp` as the matching CPU/GPU workload.
2. Dispatch batches of 65,536 coordinate pairs to the selected Vulkan compute queue.
3. Validate every GPU result against the Java result (tolerance: 0.000001).
4. Report CPU and GPU timings separately, including upload/readback time.
5. Port one vanilla-style octave noise field only after this test is correct and faster.
6. Add bounded parallel chunk batching only after vanilla-compatible noise benchmarks pass.

Terrain generation remains vanilla until step 5.
