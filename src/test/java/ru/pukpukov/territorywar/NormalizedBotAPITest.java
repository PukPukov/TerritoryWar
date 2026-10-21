package ru.pukpukov.territorywar;

import module java.base;
import org.junit.jupiter.api.Test;
import ru.pukpukov.territorywar.api.*;
import static org.junit.jupiter.api.Assertions.*;

class NormalizedBotAPITest {
    private static final int START = Constants.FIELD_SIZE * 15 / 100;
    private static final Bot IDLE = (_, _) -> Direction.NONE;

    @Test void bothSidesSeeTheSameRedStartAndCommandsAreRotatedExactlyOnce() {
        for (int player : new int[]{1, 2}) for (Direction direction : Direction.values()) {
            Bot normalized = (raw, _) -> {
                BotAPI api = raw.normalized();
                assertSame(api, api.normalized());
                assertSame(api, api.enemy().enemy());
                assertEquals(1, api.id());
                assertEquals(2, api.enemy().id());
                assertEquals(START, api.x());
                assertEquals(START, api.y());
                assertEquals(Constants.FIELD_SIZE - 1 - START, api.enemy().x());
                assertEquals(Constants.FIELD_SIZE - 1 - START, api.enemy().y());
                assertEquals(raw.tickCount(), api.tickCount());
                assertEquals(raw.enemy().tickCount(), api.enemy().tickCount());
                assertEquals(1, api.get(api.x(), api.y()));
                assertEquals(2, api.get(api.enemy().x(), api.enemy().y()));
                assertEquals(0, api.next(Direction.RIGHT));
                assertTrue(api.canMoveTo(direction));
                assertEquals(api.x() + direction.dx, api.nextX(direction));
                assertEquals(api.y() + direction.dy, api.nextY(direction));
                if (player == 1) assertSame(raw, api);
                return direction;
            };
            GameEngine game = GameEngine.builder().bot1(player == 1 ? normalized : IDLE).bot2(player == 2 ? normalized : IDLE).build();
            int x = game.bot(player).x(), y = game.bot(player).y(), sign = player == 1 ? 1 : -1;
            game.logicTick();
            assertEquals(x + sign * direction.dx, game.bot(player).x());
            assertEquals(y + sign * direction.dy, game.bot(player).y());
        }
    }

    @Test void entireFieldAndEnemyUseTheSameLiveFrameIncludingWalls() {
        GameEngine game = GameEngine.builder().bot1(IDLE).bot2(IDLE).build();
        BotAPI raw = game.bot(2), api = raw.normalized();
        game.grid()[0][0] = 1;
        game.grid()[Constants.FIELD_SIZE - 1][0] = 2;
        for (int x = -1; x <= Constants.FIELD_SIZE; x++) for (int y = -1; y <= Constants.FIELD_SIZE; y++) {
            int cell = raw.get(Constants.FIELD_SIZE - 1 - x, Constants.FIELD_SIZE - 1 - y);
            int expected = cell == 1 ? 2 : cell == 2 ? 1 : cell;
            assertEquals(expected, api.get(x, y));
            assertEquals(expected, api.enemy().get(x, y));
        }
        for (Direction direction : Direction.values()) {
            int x = Constants.FIELD_SIZE - 1 - api.nextX(direction), y = Constants.FIELD_SIZE - 1 - api.nextY(direction);
            game.grid()[x][y] = 1;
            assertEquals(2, api.next(direction));
            assertFalse(api.canMoveTo(direction));
            game.grid()[x][y] = 2;
            assertEquals(1, api.next(direction));
            assertTrue(api.canMoveTo(direction));
        }
    }

    @Test void normalizationAffectsOnlyTheCurrentBotsCurrentMove() {
        Bot mixed = (api, _) -> {
            if (api.tickCount() % 2 == 0) api.normalized().normalized();
            return Direction.RIGHT;
        };
        GameEngine game = GameEngine.builder().bot1(mixed).bot2(mixed).build();
        int blue = game.bot(2).x();
        for (int tick = 0; tick < 4; tick++) {
            game.logicTick();
            assertEquals(START + tick + 1, game.bot(1).x());
            assertEquals(blue - (tick % 2 == 0 ? 1 : 0), game.bot(2).x());
        }
    }

    @Test void liveViewAndTowardsFollowTheSameSegmentForBothSides() {
        for (int player : new int[]{1, 2}) for (int dx : new int[]{-3, -1, 0, 1, 3}) for (int dy : new int[]{-3, -1, 0, 1, 3}) {
            BotInput.Point start = new BotInput.Point(START, START), target = new BotInput.Point(START + dx, START + dy);
            Bot follows = (raw, memory) -> {
                BotAPI api = raw.normalized();
                Object previous = memory.put("view", api);
                if (previous != null) assertSame(previous, api, "No per-tick wrapper allocation");
                return api.towards(start, target);
            };
            GameEngine game = GameEngine.builder().bot1(player == 1 ? follows : IDLE).bot2(player == 2 ? follows : IDLE).build();
            BotAPI view = game.bot(player).normalized();
            for (int tick = 0; tick < Math.abs(dx) + Math.abs(dy); tick++) {
                int x = view.nextX(view.towards(start, target)), y = view.nextY(view.towards(start, target));
                game.logicTick();
                assertEquals(x, view.x());
                assertEquals(y, view.y());
                assertEquals(1, view.get(x, y));
                assertEquals(view.tickCount(), view.enemy().tickCount());
            }
            assertEquals(target.x(), view.x());
            assertEquals(target.y(), view.y());
            assertEquals(Direction.NONE, view.towards(target.x(), target.y()));
        }
    }

    @Test void runtimeCompiledNormalizedCodeWorksInBothBatchAndSearch() {
        var moving = BotCompiler.compile("api = api.normalized(); return api.x() < " + (START + 1) + " ? Direction.RIGHT : Direction.NONE;");
        var idle = BotCompiler.compile("return Direction.NONE;");
        for (int target : new int[]{1, 2}) {
            Simulations bots = target == 1 ? new Simulations(moving, idle) : new Simulations(idle, moving);
            Simulations.Result batch = bots.run(6, 0, () -> false, _ -> {});
            assertEquals(6, target == 1 ? batch.wins1() : batch.wins2());
            assertTrue(batch.best().isEmpty());
            GameReplay replay = bots.run(0, target, () -> false, _ -> {}).best().get(GameEvaluator.DIFFERENCE);
            assertEquals(2, replay.score(target));
            assertEquals(1, replay.score(3 - target));
        }
    }

    @Test void pointInputUsesPhysicalUiAndNormalizedAnswerAndUtilities() {
        for (int player : new int[]{1, 2}) {
            AtomicInteger prompts = new AtomicInteger();
            Bot asks = (raw, _) -> {
                BotAPI api = raw.normalized();
                BotInput.Point target = api.askPoint();
                assertEquals(new BotInput.Point(START + 1, START + 3), target);
                return api.towards(new BotInput.Point(START, START), target);
            };
            GameEngine game = GameEngine.builder().bot1(player == 1 ? asks : IDLE).bot2(player == 2 ? asks : IDLE)
                .input(raw -> {
                    prompts.incrementAndGet();
                    assertEquals(player, raw.id());
                    int coordinate = player == 1 ? START : Constants.FIELD_SIZE - 1 - START;
                    assertEquals(coordinate, raw.x());
                    int sign = player == 1 ? 1 : -1;
                    return new BotInput.Point(raw.x() + sign, raw.y() + 3 * sign);
                }).build();
            int before = game.bot(player).y();
            game.logicTick();
            assertEquals(1, prompts.get());
            assertEquals(before + (player == 1 ? 1 : -1), game.bot(player).y());
        }
        Bot askCancelled = (api, _) -> { assertNull(api.normalized().askPoint()); return Direction.NONE; };
        GameEngine.builder().bot1(IDLE).bot2(askCancelled).input(_ -> null).build().logicTick();
        GameEngine unavailable = GameEngine.builder().bot1(IDLE).bot2(askCancelled).build();
        assertThrows(BotInput.Unavailable.class, unavailable::logicTick);
    }

    @Test void normalizedDirectionsAreRecordedAsPhysicalMovesAndReplayMatchesEveryTick() {
        for (boolean first : new boolean[]{true, false}) {
            Bot normalized = (api, _) -> {
                api = api.normalized();
                return api.tickCount() < 12 ? Direction.MOVING.get(api.tickCount() % Direction.MOVING.size()) : null;
            };
            GameEngine original = GameEngine.builder().bot1(normalized).bot2(normalized).bot1First(first).record(true).build();
            List<Long> states = new ArrayList<>();
            while (!original.isGameOver()) { states.add(GameEngineTest.state(original)); original.logicTick(); }
            GameEngine playback = original.recording().newGame();
            for (long state : states) { assertEquals(state, GameEngineTest.state(playback)); playback.logicTick(); }
            assertEquals(GameEngineTest.state(original), GameEngineTest.state(playback));
        }
    }
}
