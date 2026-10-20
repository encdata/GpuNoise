package dev.gpunoise;

/** Output positions of density roots shared by terrain and material decisions. */
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
    public static final int VEIN_TOGGLE = 12;
    public static final int VEIN_RIDGED = 13;
    public static final int VEIN_GAP = 14;
    public static final int COUNT = 15;
    private static final String[] NAMES = {"finalDensity", "initialDensity", "barrier", "floodedness",
            "spread", "lava", "erosion", "depth", "temperature", "vegetation", "continents", "ridges",
            "veinToggle", "veinRidged", "veinGap"};

    public static String name(int root) {
        return root >= 0 && root < NAMES.length ? NAMES[root] : "unknown";
    }

    private GpuTerrainRoots() {}
}
