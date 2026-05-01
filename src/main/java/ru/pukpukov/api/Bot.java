package ru.pukpukov.api;

import java.util.Map;

public interface Bot {
    
    Direction move(BotAPI api, Map<String, Object> mem);
    
}