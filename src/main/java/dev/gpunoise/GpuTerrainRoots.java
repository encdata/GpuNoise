package dev.gpunoise;

/** Output positions of the compact graph used by the chunk terrain path. */
public final class GpuTerrainRoots {
    public static final int FINAL_DENSITY = 0;
    public static final int INITIAL_DENSITY = 1;
    public static final int BARRIER = 2;
    public static final int FLOODEDNESS = 3;
    public static final int SPREAD = 4;
    public static final int LAVA = 5;
    public static final int EROSION = 6;
    public static final int DEPTH = 7;
    public static final int TEMPERATURE = 8;
    public static final int VEGETATION = 9;
    public static final int CONTINENTS = 10;
    public static final int RIDGES = 11;
    public static final int COUNT = 12;

    private GpuTerrainRoots() {}
}
