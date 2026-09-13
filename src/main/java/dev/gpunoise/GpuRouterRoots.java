package dev.gpunoise;

/** Stable output positions of {@link GpuDensityGraphCompiler#compile}. */
public final class GpuRouterRoots {
    public static final int BARRIER = 0;
    public static final int FLOODEDNESS = 1;
    public static final int SPREAD = 2;
    public static final int LAVA = 3;
    public static final int TEMPERATURE = 4;
    public static final int VEGETATION = 5;
    public static final int CONTINENTS = 6;
    public static final int EROSION = 7;
    public static final int DEPTH = 8;
    public static final int RIDGES = 9;
    public static final int INITIAL_DENSITY = 10;
    public static final int FINAL_DENSITY = 11;
    public static final int VEIN_TOGGLE = 12;
    public static final int VEIN_RIDGED = 13;
    public static final int VEIN_GAP = 14;
    public static final int COUNT = 15;

    private GpuRouterRoots() {}
}
