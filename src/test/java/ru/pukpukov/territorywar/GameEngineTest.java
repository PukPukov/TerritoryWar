package ru.pukpukov.territorywar;

import module java.base;
import org.junit.jupiter.api.Test;
import ru.pukpukov.territorywar.api.*;
import static org.junit.jupiter.api.Assertions.*;

class GameEngineTest {
    static Bot bot(int kind) {
        return switch (kind) {
            case 0 -> (api, mem) -> null;
            case 1 -> (api, mem) -> Direction.values()[(api.tickCount() / 201) % 4];
            default -> (api, mem) -> {
                Direction horizontal = api.y() % 2 == 0 ? Direction.RIGHT : Direction.LEFT;
                return api.next(horizontal) == -1 ? Direction.DOWN : horizontal;
            };
        };
    }

    @Test void originalEngineParity() {
        // Captured before refactoring: all ticks' positions/scores, then every final grid cell.
        long[][] fixtures = {{0, 0, 128, 1, 1, 134081369L}, {1, 2, 759, 566, 369, 2486636960L}, {2, 2, 27927, 33830, 6170, 46580896L}};
        for (long[] f : fixtures) for (boolean first : new boolean[]{true, false}) {
            GameEngine game = GameEngine.builder().bot1(bot((int) f[0])).bot2(bot((int) f[1])).bot1First(first).record(false).build();
            CRC32 crc = new CRC32();
            int ticks = 0;
            while (!game.isGameOver()) {
                game.logicTick();
                assertTrue(++ticks <= 100000);
                for (int n : new int[]{game.bot(1).x(), game.bot(1).y(), game.bot(2).x(), game.bot(2).y(), game.score(1), game.score(2)}) {
                    for (int shift = 0; shift < 32; shift += 8) crc.update(n >>> shift);
                }
            }
            for (int[] column : game.grid()) for (int cell : column) crc.update(cell);
            assertEquals(f[2], ticks);
            assertEquals(f[3], game.score(1));
            assertEquals(f[4], game.score(2));
            assertEquals(f[5], crc.getValue());
        }
    }

    static long state(GameEngine game) {
        CRC32 crc = new CRC32();
        for (int id = 1; id <= 2; id++) {
            BotAPI bot = game.bot(id);
            for (int n : new int[]{bot.x(), bot.y(), bot.tickCount(), game.score(id), game.winner(), game.isGameOver() ? 1 : 0}) {
                for (int shift = 0; shift < 32; shift += 8) crc.update(n >>> shift);
            }
        }
        for (int[] column : game.grid()) for (int cell : column) crc.update(cell);
        return crc.getValue();
    }

    @Test void replayMatchesEveryTickWithoutRunningRandomBotsAgain() {
        for (boolean first : new boolean[]{true, false}) {
            AtomicBoolean finished = new AtomicBoolean();
            Bot random = (api, mem) -> {
                assertFalse(finished.get(), "Playback must not run the original bot");
                assertSame(api, api.enemy().enemy());
                if (api.tickCount() > 600) return null;
                return Direction.values()[ThreadLocalRandom.current().nextInt(4)];
            };
            GameEngine original = GameEngine.builder().bot1(random).bot2(random).bot1First(first).record(true).build();
            List<Long> states = new ArrayList<>();
            states.add(state(original));
            while (!original.isGameOver()) {
                original.logicTick();
                states.add(state(original));
            }
            finished.set(true);
            GameReplay recording = original.recording();
            for (int repeat = 0; repeat < 2; repeat++) {
                GameEngine playback = recording.newGame();
                for (long expected : states) {
                    assertEquals(expected, state(playback));
                    playback.logicTick();
                }
                assertTrue(playback.isGameOver());
            }
        }
    }

    @Test void replayIncludesFinalFloodFill() {
        GameEngine original = GameEngine.builder().bot1(bot(2)).bot2(bot(2)).bot1First(false).record(true).build();
        while (!original.isGameOver()) original.logicTick();
        GameEngine replay = original.recording().newGame();
        while (!replay.isGameOver()) replay.logicTick();
        assertEquals(state(original), state(replay));
        assertEquals(GameEngine.COLS * GameEngine.ROWS, replay.score(1) + replay.score(2));
    }

    @Test void replayRecordsSkippedExceptionMoves() {
        var logger = java.util.logging.Logger.getLogger(GameEngine.class.getName());
        var previousLevel = logger.getLevel();
        logger.setLevel(java.util.logging.Level.OFF);
        try {
            Bot broken = (api, mem) -> { throw new IllegalArgumentException("bad bot move"); };
            GameEngine original = GameEngine.builder().bot1(broken).bot2(bot(0)).bot1First(true).record(true).build();
            while (!original.isGameOver()) original.logicTick();
            GameEngine replay = original.recording().newGame();
            while (!replay.isGameOver()) replay.logicTick();
            assertEquals(state(original), state(replay));
            assertEquals(GameEngine.MAX_IDLE_TICKS, replay.bot(1).tickCount());
        } finally { logger.setLevel(previousLevel); }
    }

    @Test void normalGamesDoNotAllocateRecordings() throws Exception {
        GameEngine normal = GameEngine.builder().bot1(bot(0)).bot2(bot(0)).build();
        var moves = GameEngine.class.getDeclaredField("moves");
        moves.setAccessible(true);
        assertNull(moves.get(normal));
        while (!normal.isGameOver()) normal.logicTick();
        assertNull(moves.get(normal));
        assertThrows(AssertionError.class, normal::recording);
    }

    @Test void directionsAgreeWithApiAndMovement() {
        for (Direction direction : Direction.values()) {
            GameEngine game = GameEngine.builder().bot1((api, mem) -> direction).bot2(bot(0)).bot1First(true).record(false).build();
            int x = game.bot(1).nextX(direction), y = game.bot(1).nextY(direction);
            game.logicTick();
            assertEquals(x, game.bot(1).x());
            assertEquals(y, game.bot(1).y());
            assertEquals(2, game.score(1));
        }
    }

    @Test void alternatingTurnOrderDecidesSimultaneousArrival() {
        Bot approach = (api, mem) -> api.x() < 99 ? Direction.RIGHT : api.x() > 99 ? Direction.LEFT : api.y() < 100 ? Direction.DOWN : Direction.UP;
        for (boolean first : new boolean[]{true, false}) {
            GameEngine game = GameEngine.builder().bot1(approach).bot2(approach).bot1First(first).record(false).build();
            for (int tick = 0; tick < 139; tick++) game.logicTick();
            assertEquals(first ? 1 : 2, game.grid()[99][100]);
        }
    }

    @Test void fatalErrorsAreNotDisguisedAsSkippedMoves() {
        GameEngine game = GameEngine.builder().bot1((api, mem) -> { throw new AssertionError("broken invariant"); }).bot2(bot(0)).bot1First(true).record(false).build();
        assertThrows(AssertionError.class, game::logicTick);
    }
}
