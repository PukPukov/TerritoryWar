package com.territorywar.api;

import java.util.Map;

public interface BotAPI {
    int getMyId();
    int getMyX();
    int getMyY();
    Map<String, Object> getMemory();
    int getGrid(int x, int y);
    int getNext(Direction dir);
}