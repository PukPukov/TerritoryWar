package ru.pukpukov.territorywar;

import org.junit.jupiter.api.Test;
import ru.pukpukov.territorywar.api.Direction;
import static org.junit.jupiter.api.Assertions.*;

class GameLibraryTest {
    @Test void evaluatorsRankByDifferentCriteria() {
        assertTrue(GameEvaluator.DIFFERENCE.value(10, 12) > GameEvaluator.DIFFERENCE.value(20, 30));
        assertTrue(GameEvaluator.MAXIMUM.value(20, 30) > GameEvaluator.MAXIMUM.value(10, 12));
    }

    @Test void libraryKeepsOrderedImmutableSnapshotsAndPlayableRecordings() {
        GameEngine game = GameEngine.builder().bot1((_, _) -> Direction.RIGHT).bot2((_, _) -> null).record(true).build();
        while (!game.isGameOver()) game.logicTick();
        GameReplay recording = game.recording();
        GameLibrary library = new InMemoryGameLibrary();
        var first = new GameLibrary.Entry("Игра 1", recording);
        var second = new GameLibrary.Entry("Игра 2", recording);
        assertTrue(library.entries().isEmpty());
        library.add(first);
        var snapshot = library.entries();
        library.add(second);
        assertEquals(java.util.List.of(first), snapshot);
        assertEquals(java.util.List.of(first, second), library.entries());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(second));
        GameEngine playback = snapshot.getFirst().replay().newGame();
        while (!playback.isGameOver()) playback.logicTick();
        for (int player : new int[]{1, 2}) {
            assertEquals(game.score(player), recording.score(player));
            assertEquals(game.score(player), playback.score(player));
        }
    }
}
