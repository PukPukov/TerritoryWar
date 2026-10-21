package ru.pukpukov.territorywar.api;

import lombok.RequiredArgsConstructor;
import java.util.Arrays;
import java.util.List;

@RequiredArgsConstructor
public enum Direction {
    RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0), UP(0, -1), NONE(0, 0);

    public static final List<Direction> MOVING = Arrays.stream(values()).filter(direction -> direction != NONE).toList();
    public final int dx, dy;

    /** No pathfinding: horizontal first, then vertical, or NONE at the target. */
    public static Direction towards(int dx, int dy) {
        if (dx != 0) return dx > 0 ? RIGHT : LEFT;
        if (dy != 0) return dy > 0 ? DOWN : UP;
        return NONE;
    }
    
}
