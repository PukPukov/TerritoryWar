package ru.pukpukov.territorywar;

import java.util.ArrayList;
import java.util.List;

/** One application's session; the UI serializes access on its own thread. */
public final class InMemoryGameLibrary implements GameLibrary {
    private final List<Entry> games = new ArrayList<>();

    @Override public void add(Entry game) { games.add(game); }
    @Override public List<Entry> entries() { return List.copyOf(games); }
}
