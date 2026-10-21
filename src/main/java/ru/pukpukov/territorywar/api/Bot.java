package ru.pukpukov.territorywar.api;

import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

public interface Bot {
    
    @Nullable Direction move(BotAPI api, Map<String, Object> mem);
    
}