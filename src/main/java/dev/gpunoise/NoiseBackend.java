package dev.gpunoise;

/** Backends that can evaluate terrain noise. GPU support will implement this boundary. */
public enum NoiseBackend {
    CPU,
    GPU
}
