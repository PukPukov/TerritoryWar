package ru.pukpukov.territorywar;

import module java.base;
import org.junit.jupiter.api.Test;
import ru.pukpukov.territorywar.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SimulationsTest {
    public static class Idle implements Bot {
        public Direction move(BotAPI api, Map<String, Object> mem) { return null; }
    }
    public static class RareWinner implements Bot {
        static final AtomicInteger instances = new AtomicInteger();
        final boolean wins = instances.incrementAndGet() % 3 == 0;
        public Direction move(BotAPI api, Map<String, Object> mem) { return wins ? Direction.RIGHT : null; }
    }
    public static class Broken extends Idle {
        public Broken() { throw new IllegalStateException("constructor failed"); }
    }

    @Test void finiteBatchHonorsCountAndDoesNotRecord() {
        Simulations bots = new Simulations(Idle.class, Idle.class);
        AtomicInteger progress = new AtomicInteger();
        Simulations.Result result = bots.run(17, 0, () -> false, done -> assertEquals(progress.incrementAndGet(), done));
        assertEquals(17, result.count());
        assertEquals(17, result.draws());
        assertNull(result.replay());
    }

    @Test void searchesForEitherBotAndKeepsOnlyWinningGame() {
        for (int winner : new int[]{1, 2}) {
            RareWinner.instances.set(0);
            Simulations bots = winner == 1 ? new Simulations(RareWinner.class, Idle.class) : new Simulations(Idle.class, RareWinner.class);
            Simulations.Result result = bots.run(0, winner, () -> false, done -> {});
            assertEquals(3, result.count());
            assertEquals(2, result.draws());
            assertNotNull(result.replay());
            GameEngine playback = result.replay().newGame();
            while (!playback.isGameOver()) playback.logicTick();
            assertEquals(winner, playback.winner());
        }
    }

    @Test void neverWinningSearchCanBeCancelled() {
        AtomicBoolean cancelled = new AtomicBoolean();
        assertThrows(CancellationException.class, () -> new Simulations(Idle.class, Idle.class)
            .run(0, 1, cancelled::get, done -> { if (done == 5) cancelled.set(true); }));
    }

    @Test void cancellationIsCheckedInsideGame() {
        AtomicInteger polls = new AtomicInteger();
        assertThrows(CancellationException.class, () -> new Simulations(Idle.class, Idle.class)
            .run(0, 1, () -> polls.incrementAndGet() >= 4, done -> fail("Incomplete game counted")));
        assertEquals(4, polls.get());
    }

    @Test void constructorFailuresAreReportedInsteadOfCountedAsSuccess() {
        assertThrows(IllegalStateException.class, () -> new Simulations(Broken.class, Idle.class).run(1, 0, () -> false, done -> fail()));
    }

    @Test void parallelFailureKeepsOriginalCause() {
        for (int repeat = 0; repeat < 30; repeat++) {
            RuntimeException error = assertThrows(RuntimeException.class, () -> new Simulations(Broken.class, Idle.class)
                .run(100, 0, () -> false, done -> {}));
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            assertEquals("constructor failed", cause.getMessage());
        }
    }

    @Test void validatesBatchCountAndWinner() {
        Simulations bots = new Simulations(Idle.class, Idle.class);
        assertThrows(IllegalArgumentException.class, () -> bots.run(0, 0, () -> false, done -> {}));
        assertThrows(IllegalArgumentException.class, () -> bots.run(1, 3, () -> false, done -> {}));
    }
}
