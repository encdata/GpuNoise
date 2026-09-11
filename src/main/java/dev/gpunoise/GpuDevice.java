package dev.gpunoise;

/** A Vulkan device discovered by the server-side capability probe. */
record GpuDevice(int vendorId, String vendorName, String name, int priority) {}
