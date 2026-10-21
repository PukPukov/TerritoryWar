package ru.pukpukov.territorywar.api;

public interface BotAPI {
    
    BotAPI enemy();
    int tickCount();
    int id();
    int x();
    int y();
    int get(int x, int y);
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