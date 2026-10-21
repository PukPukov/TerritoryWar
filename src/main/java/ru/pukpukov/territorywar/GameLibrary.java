package ru.pukpukov.territorywar;

import java.util.List;

/** Storage boundary, independent of JavaFX. A disk-backed library can implement the same two operations. */
public interface GameLibrary {
    record Entry(String title, GameReplay replay) {
        @Override public String toString() {
            return title + " [" + replay.score(GameEngine.PLAYER_1) + ":" + replay.score(GameEngine.PLAYER_2) + "]";
        }
    }

    void add(Entry game);
    /** Immutable snapshot in insertion order. */
    List<Entry> entries();
}
