package com.gathin.tvhome;

/**
 * Up and Down between the dock row and a grid whose rows hold a different number of tiles (grid_columns). The
 * first {@code dock} positions are the dock, then rows of {@code grid} tiles. A move between the dock and the
 * first grid row lands on the tile under the same part of the screen. With dock == grid this is the plain
 * "one row up or down" of a uniform grid.
 */
final class GridRows {
    private GridRows() { }

    /** The position above, or -1 from the dock row (focus leaves for the top bar). */
    static int up(int position, int dock, int grid) {
        if (position < dock) return -1;
        if (position < dock + grid) return Math.min(dock - 1, (int) ((position - dock + 0.5f) * dock / grid));
        return position - grid;
    }

    /** The position below, or -1 on the last row. */
    static int down(int position, int count, int dock, int grid) {
        if (position < dock) {
            if (count <= dock) return -1;
            return Math.min(count - 1, dock + (int) ((position + 0.5f) * grid / dock));
        }
        int lastRowStart = dock + ((count - 1 - dock) / grid) * grid;
        if (position >= lastRowStart) return -1;
        return Math.min(position + grid, count - 1);
    }

    /** The first position of the last row. */
    static int lastRowStart(int count, int dock, int grid) {
        if (count <= dock) return 0;
        return dock + ((count - 1 - dock) / grid) * grid;
    }

    /** The column a position sits in within its own row. */
    static int column(int position, int dock, int grid) {
        return position < dock ? position : (position - dock) % grid;
    }

    /** Spans for a GridLayoutManager that lays out both: the least common multiple of the two row lengths. */
    static int spanCount(int dock, int grid) {
        int a = dock, b = grid;
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return dock / a * grid;
    }
}
