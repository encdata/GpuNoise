package dev.gpunoise;

/** A Vulkan device discovered by the server-side capability probe. */
record GpuDevice(int enumerationIndex, int vendorId, int deviceId, int driverVersion,
                 String vendorName, String name, int priority, int deviceType,
                 boolean shaderFloat64) {
    String diagnosticIdentity() {
        return "%s [%s, vendor=0x%04X, device=0x%04X, driver=0x%08X]"
                .formatted(name, vendorName, vendorId, deviceId, driverVersion);
    }
}
