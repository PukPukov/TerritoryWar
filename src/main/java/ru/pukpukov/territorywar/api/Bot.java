package ru.pukpukov.territorywar.api;

import java.util.Map;

public interface Bot {
    
    Direction move(BotAPI api, Map<String, Object> mem);
    
}