package ru.pukpukov.territorywar.api;

import org.checkerframework.checker.nullness.qual.Nullable;

/** Optional UI capability. Batch simulations deliberately do not provide it. */
@FunctionalInterface
public interface BotInput {
    @Nullable Point askPoint(BotAPI bot);

    record Point(int x, int y) {
        public Point {
            if (x < 0 || y < 0 || x >= Constants.FIELD_SIZE || y >= Constants.FIELD_SIZE) {
                throw new IllegalArgumentException("Точка за пределами поля: " + x + ", " + y);
            }
        }
    }

    class Failure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public Failure(String message, @Nullable Throwable cause) { super(message, cause); }
    }

    final class Unavailable extends Failure {
        private static final long serialVersionUID = 1L;
        public Unavailable() { super("Ручной ввод доступен только в обычной игре, не в поиске или пакетных симуляциях", null); }
    }
}
