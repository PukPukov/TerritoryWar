package ru.pukpukov.territorywar.api;

public interface BotAPI {
    
    int id();
    int x();
    int y();
    int get(int x, int y);
    int nextX(Direction dir);
    int nextY(Direction dir);
    
    default int next(Direction dir) {
        return get(nextX(dir), nextY(dir));
    }
    
    default boolean canMoveTo(Direction direction) {
        int data = this.next(direction);
        return data == 0 || data == this.id();
    }
    
}