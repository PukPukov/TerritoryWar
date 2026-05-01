package ru.pukpukov.territorywar.api;

public interface BotAPI {
    
    int id();
    int x();
    int y();
    int get(int x, int y);
    int next(Direction dir);
    
}