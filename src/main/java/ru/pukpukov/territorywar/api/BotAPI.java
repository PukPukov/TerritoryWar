package ru.pukpukov.territorywar.api;

import org.checkerframework.checker.nullness.qual.Nullable;

public interface BotAPI {
    
    BotAPI enemy();
    int tickCount();
    int id();
    int x();
    int y();
    int get(int x, int y);
    /** Blocks the calling bot, never the UI thread; null means the user cancelled. */
    BotInput.@Nullable Point askPoint();
    
    default Direction towards(int x, int y) { return Direction.towards(x - x(), y - y()); }

    /** Cardinal staircase along the segment, preserving its original X:Y ratio. */
    default Direction towards(BotInput.Point start, BotInput.Point target) {
        int dx = Math.abs(target.x() - start.x()), dy = Math.abs(target.y() - start.y());
        int movedX = Math.abs(x() - start.x()), movedY = Math.abs(y() - start.y());
        boolean horizontal = (2 * movedX + 1) * dy <= (2 * movedY + 1) * dx;
        return Direction.towards(horizontal ? target.x() - x() : 0, horizontal ? 0 : target.y() - y());
    }

    default int nextX(Direction dir) { return x() + dir.dx; }
    default int nextY(Direction dir) { return y() + dir.dy; }
    
    default int next(Direction dir) {
        return get(nextX(dir), nextY(dir));
    }
    
    default boolean canMoveTo(Direction direction) {
        int data = this.next(direction);
        return data == 0 || data == this.id();
    }
    
}