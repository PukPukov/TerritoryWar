package ru.pukpukov.territorywar.api;

public interface BotAPI {
    
    int id();
    int x();
    int y();
    int get(int x, int y);
    int next(Direction dir);
    
    default boolean canMoveTo(Direction direction) {
        int data = this.next(direction);
        return data == 0 || data == this.id();
    }
    
}