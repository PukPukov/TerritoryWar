package ru.pukpukov.territorywar.api;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum Direction {
    RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0), UP(0, -1);

    public final int dx, dy;
    
}
