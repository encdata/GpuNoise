package dev.gpunoise;

/** A Vulkan device discovered by the server-side capability probe. */
record GpuDevice(int enumerationIndex, int vendorId, int deviceId, String vendorName,
                 String name, int priority, boolean shaderFloat64) {}
