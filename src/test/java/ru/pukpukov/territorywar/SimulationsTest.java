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

    /** Controlled test workload: winner cannot complete before another worker starts. */
    public static class ParallelWinner implements Bot {
        static AtomicInteger instances = new AtomicInteger(), active = new AtomicInteger();
        static CountDownLatch peers = new CountDownLatch(2), release = new CountDownLatch(1);
        static Set<Long> threads = ConcurrentHashMap.newKeySet();
        final boolean wins = instances.incrementAndGet() == 1;

        protected CountDownLatch gate() { return wins ? peers : release; }

        static void reset() {
            instances.set(0);
            active.set(0);
            peers = new CountDownLatch(2);
            release = new CountDownLatch(1);
            threads.clear();
        }

        @Override public Direction move(BotAPI api, Map<String, Object> memory) {
            active.incrementAndGet();
            try {
                if (api.tickCount() == 0) {
                    threads.add(Thread.currentThread().threadId());
                    peers.countDown();
                    assertTrue(gate().await(5, TimeUnit.SECONDS), "Search needs concurrent workers");
                }
                return wins ? Direction.RIGHT : Direction.NONE;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } finally { active.decrementAndGet(); }
        }
    }

    /** No game can finish until the cancelling test explicitly releases the workers. */
    public static class ParallelWaiting extends ParallelWinner {
        @Override protected CountDownLatch gate() { return release; }
    }

    @Test void searchRunsConcurrentlyAndJoinsUnfinishedPeersAfterWin() {
        for (int winner : new int[]{1, 2}) {
            ParallelWinner.reset();
            Simulations simulations = winner == 1 ? new Simulations(ParallelWinner.class, Idle.class) : new Simulations(Idle.class, ParallelWinner.class);
            Simulations.Result result;
            try { result = simulations.run(0, winner, () -> false, done -> ParallelWinner.release.countDown()); }
            finally { ParallelWinner.release.countDown(); }
            assertTrue(ParallelWinner.threads.size() >= 2);
            assertEquals(0, ParallelWinner.active.get(), "No bot code may still be running when search returns");
            assertEquals(1, result.count(), "Unfinished attempts must not enter statistics");
            assertFalse(result.best().isEmpty());
            GameEngine replay = result.best().get(GameEvaluator.DIFFERENCE).newGame();
            while (!replay.isGameOver()) replay.logicTick();
            assertEquals(winner, replay.winner());
        }
    }

    @Test void finiteBatchHonorsCountAndDoesNotRecord() {
        Simulations bots = new Simulations(Idle.class, Idle.class);
        AtomicInteger progress = new AtomicInteger();
        Simulations.Result result = bots.run(17, 0, () -> false, done -> assertEquals(progress.incrementAndGet(), done));
        assertEquals(17, result.count());
        assertEquals(17, result.draws());
        assertTrue(result.best().isEmpty());
    }

    @Test void searchesForEitherBotAndKeepsWinningDifference() {
        for (int winner : new int[]{1, 2}) {
            RareWinner.instances.set(0);
            Simulations bots = winner == 1 ? new Simulations(RareWinner.class, Idle.class) : new Simulations(Idle.class, RareWinner.class);
            Simulations.Result result = bots.run(0, winner, () -> false, done -> {});
            assertEquals(1, winner == 1 ? result.wins1() : result.wins2());
            assertEquals(0, winner == 1 ? result.wins2() : result.wins1());
            assertEquals(result.count() - 1, result.draws());
            assertFalse(result.best().isEmpty());
            GameEngine playback = result.best().get(GameEvaluator.DIFFERENCE).newGame();
            while (!playback.isGameOver()) playback.logicTick();
            assertEquals(winner, playback.winner());
        }
    }

    @Test void cancelledSearchKeepsACompletedReplay() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Simulations.Result result = assertDoesNotThrow(() -> new Simulations(Idle.class, Idle.class)
            .run(0, 1, cancelled::get, done -> { if (done == 5) cancelled.set(true); }));
        assertEquals(5, result.count());
        assertTrue(result.cancelled());
        assertEquals(2, result.best().size());
        assertSame(result.best().get(GameEvaluator.DIFFERENCE), result.best().get(GameEvaluator.MAXIMUM), "One match should not be copied for two evaluators");
        GameReplay recording = result.best().get(GameEvaluator.DIFFERENCE);
        assertEquals(1, recording.score(1));
        assertEquals(1, recording.score(2));
        GameEngine replay = recording.newGame();
        while (!replay.isGameOver()) replay.logicTick();
        assertEquals(0, replay.winner());
    }

    /** Exactly two completed losing matches: scores 1:3 and 2:11 for the target. */
    public static class ScoredTarget implements Bot {
        static AtomicInteger instances = new AtomicInteger();
        static CountDownLatch release = new CountDownLatch(1);
        final int index = instances.getAndIncrement();

        @Override public Direction move(BotAPI api, Map<String, Object> memory) {
            if (index >= 2 && api.tickCount() == 0) {
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            return index == 1 && api.tickCount() == 0 ? Direction.RIGHT : Direction.NONE;
        }
    }
    public static class ScoredOpponent implements Bot {
        @Override public Direction move(BotAPI api, Map<String, Object> memory) {
            BotAPI target = api.enemy();
            int steps = target.next(Direction.LEFT) == target.id() ? 10 : 2;
            return api.tickCount() > 0 && api.tickCount() <= steps ? Direction.LEFT : Direction.NONE;
        }
    }

    @Test void cancelledSearchKeepsSeparateBestsForEachEvaluatorAndTargetSide() {
        for (int target : new int[]{1, 2}) {
            ScoredTarget.instances.set(0);
            ScoredTarget.release = new CountDownLatch(1);
            AtomicBoolean cancelled = new AtomicBoolean();
            Simulations bots = target == 1 ? new Simulations(ScoredTarget.class, ScoredOpponent.class) : new Simulations(ScoredOpponent.class, ScoredTarget.class);
            Simulations.Result result;
            try {
                result = bots.run(0, target, cancelled::get, done -> {
                    if (done == 2) { cancelled.set(true); ScoredTarget.release.countDown(); }
                });
            } finally { ScoredTarget.release.countDown(); }
            assertTrue(result.cancelled());
            assertEquals(2, result.count());
            GameReplay difference = result.best().get(GameEvaluator.DIFFERENCE), maximum = result.best().get(GameEvaluator.MAXIMUM);
            assertNotSame(difference, maximum);
            assertEquals(1, difference.score(target));
            assertEquals(3, difference.score(3 - target));
            assertEquals(2, maximum.score(target));
            assertEquals(11, maximum.score(3 - target));
            assertThrows(UnsupportedOperationException.class, () -> result.best().clear());
            for (GameReplay recording : result.best().values()) {
                GameEngine replay = recording.newGame();
                while (!replay.isGameOver()) replay.logicTick();
                for (int player : new int[]{1, 2}) assertEquals(recording.score(player), replay.score(player));
            }
        }
    }

    @Test void cancellationIsCheckedInsideGame() {
        AtomicInteger polls = new AtomicInteger();
        Simulations.Result result = new Simulations(Idle.class, Idle.class)
            .run(0, 1, () -> polls.incrementAndGet() >= 4, done -> fail("Incomplete game counted"));
        assertTrue(result.cancelled());
        assertTrue(result.best().isEmpty());
        assertEquals(0, result.count());
        assertTrue(polls.get() >= 4);
    }

    @Test void cancellationJoinsAllActiveSearchWorkers() throws Exception {
        ParallelWinner.reset();
        AtomicBoolean cancelled = new AtomicBoolean();
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Simulations.Result> future = executor.submit(() -> new Simulations(ParallelWaiting.class, Idle.class)
                .run(0, 1, cancelled::get, done -> fail("Cancelled game must not count")));
            try {
                assertTrue(ParallelWinner.peers.await(5, TimeUnit.SECONDS));
                cancelled.set(true);
            } finally { ParallelWinner.release.countDown(); }
            Simulations.Result result = future.get(5, TimeUnit.SECONDS);
            assertTrue(result.cancelled());
            assertTrue(result.best().isEmpty());
            assertEquals(0, ParallelWinner.active.get());
        }
    }

    @Test void constructorFailuresAreReportedInsteadOfCountedAsSuccess() {
        assertThrows(IllegalStateException.class, () -> new Simulations(Broken.class, Idle.class).run(1, 0, () -> false, done -> fail()));
    }

    @Test void parallelFailureKeepsOriginalCause() {
        for (int repeat = 0; repeat < 30; repeat++) {
            int target = repeat % 2;
            RuntimeException error = assertThrows(RuntimeException.class, () -> new Simulations(Broken.class, Idle.class)
                .run(100, target, () -> false, done -> {}));
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
