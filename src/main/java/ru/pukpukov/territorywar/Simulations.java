package ru.pukpukov.territorywar;

import module java.base;
import org.checkerframework.checker.nullness.qual.Nullable;
import ru.pukpukov.territorywar.api.Bot;
import ru.pukpukov.territorywar.api.BotInput;

/** A compiled pair is immutable; every game gets fresh bot instances and memory. */
public record Simulations(Class<? extends Bot> bot1, Class<? extends Bot> bot2) {
    public record Result(int wins1, int wins2, int draws, Map<GameEvaluator, GameReplay> best, boolean cancelled) {
        public int count() { return wins1 + wins2 + draws; }
    }

    public GameEngine newGame(boolean record, @Nullable BotInput input) {
        try {
            return GameEngine.builder()
                .bot1(bot1.getDeclaredConstructor().newInstance())
                .bot2(bot2.getDeclaredConstructor().newInstance())
                .record(record)
                .input(input).build();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Не удалось создать ботов", e);
        }
    }

    /** winner=0: a finite parallel batch; winner=1/2: search until a strict win or cancellation. */
    public Result run(int count, int winner, BooleanSupplier cancelled, IntConsumer progress) {
        if (winner < 0 || winner > 2 || (winner == 0 && count <= 0)) throw new IllegalArgumentException("Неверные параметры симуляций");
        int[] results = new int[3];
        AtomicInteger remaining = new AtomicInteger(count);
        Map<GameEvaluator, GameEngine> best = new EnumMap<>(GameEvaluator.class);
        AtomicBoolean found = new AtomicBoolean();
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        BooleanSupplier stopped = () -> cancelled.getAsBoolean() || found.get() || failure.get() != null;
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors());
        if (winner == 0) workers = Math.min(workers, count);
        IntStream.range(0, workers).parallel().forEach(worker -> {
            try {
                while (!stopped.getAsBoolean() && (winner != 0 || remaining.getAndDecrement() > 0)) {
                    GameEngine game = newGame(winner != 0, null);
                    while (!game.isGameOver() && !stopped.getAsBoolean()) game.logicTick();
                    if (!game.isGameOver()) return;
                    synchronized (results) {
                        if (stopped.getAsBoolean()) return;
                        results[game.winner()] = Math.incrementExact(results[game.winner()]);
                        if (winner != 0) {
                            for (GameEvaluator evaluator : GameEvaluator.ALL) {
                                GameEngine previous = best.get(evaluator);
                                if (previous == null || evaluate(evaluator, game, winner) > evaluate(evaluator, previous, winner)) best.put(evaluator, game);
                            }
                            found.set(game.winner() == winner);
                        }
                        progress.accept(Math.addExact(Math.addExact(results[0], results[1]), results[2]));
                    }
                }
            } catch (RuntimeException | Error e) {
                failure.compareAndSet(null, e); // Neighbours exit normally; all workers are joined before returning.
            }
        });
        Throwable error = failure.get();
        if (error instanceof RuntimeException e) throw e;
        if (error instanceof Error e) throw e;
        assert error == null : "Unexpected checked failure";
        Map<GameEngine, GameReplay> recordings = new IdentityHashMap<>();
        Map<GameEvaluator, GameReplay> selected = new EnumMap<>(GameEvaluator.class);
        best.forEach((evaluator, game) -> selected.put(evaluator, recordings.computeIfAbsent(game, GameEngine::recording)));
        return new Result(results[1], results[2], results[0], Collections.unmodifiableMap(selected), cancelled.getAsBoolean());
    }
    private static int evaluate(GameEvaluator evaluator, GameEngine game, int player) {
        return evaluator.value(game.score(player), game.score(game.bot(player).enemy().id()));
    }

}
