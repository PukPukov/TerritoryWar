package ru.pukpukov.territorywar;

import module java.base;
import org.checkerframework.checker.nullness.qual.Nullable;
import ru.pukpukov.territorywar.api.Bot;

/** A compiled pair is immutable; every game gets fresh bot instances and memory. */
public record Simulations(Class<? extends Bot> bot1, Class<? extends Bot> bot2) {
    public record Result(int wins1, int wins2, int draws, @Nullable GameReplay replay) {
        public int count() { return wins1 + wins2 + draws; }
    }

    public GameEngine newGame(boolean record) {
        try {
            return GameEngine.builder()
                .bot1(bot1.getDeclaredConstructor().newInstance())
                .bot2(bot2.getDeclaredConstructor().newInstance())
                .record(record).build();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Не удалось создать ботов", e);
        }
    }

    /** winner=0: a finite parallel batch; winner=1/2: search until a strict win or cancellation. */
    public Result run(int count, int winner, BooleanSupplier cancelled, IntConsumer progress) {
        if (winner < 0 || winner > 2 || (winner == 0 && count <= 0)) throw new IllegalArgumentException("Неверные параметры симуляций");
        int[] results = new int[3];
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Runnable checkStopped = () -> {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            @Nullable Throwable cause = failure.get();
            if (cause != null) throw new IllegalStateException("Симуляции прерваны из-за ошибки", cause);
        };
        Supplier<GameEngine> play = () -> {
            try {
                checkStopped.run();
                GameEngine game = newGame(winner != 0);
                while (!game.isGameOver()) {
                    checkStopped.run();
                    game.logicTick();
                }
                synchronized (results) {
                    results[game.winner()]++;
                    progress.accept(results[0] + results[1] + results[2]);
                }
                return game;
            } catch (RuntimeException | Error e) {
                failure.compareAndSet(null, e);
                throw e;
            }
        };
        GameReplay replay = null;
        if (winner == 0) IntStream.range(0, count).parallel().forEach(i -> play.get());
        else {
            GameEngine game;
            do { game = play.get(); } while (game.winner() != winner);
            replay = game.recording();
        }
        return new Result(results[1], results[2], results[0], replay);
    }
}
