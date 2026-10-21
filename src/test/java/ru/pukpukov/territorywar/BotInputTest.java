package ru.pukpukov.territorywar;

import module java.base;
import org.junit.jupiter.api.Test;
import ru.pukpukov.territorywar.api.*;
import static org.junit.jupiter.api.Assertions.*;

class BotInputTest {
    @Test void noInputCapabilityByDefaultAndCancellationIsNotSwallowed() {
        Bot asks = (api, _) -> { api.askPoint(); return Direction.NONE; };
        GameEngine headless = GameEngine.builder().bot1(asks).bot2((_, _) -> Direction.NONE).bot1First(true).build();
        assertThrows(BotInput.Unavailable.class, headless::logicTick);
        GameEngine cancelled = GameEngine.builder().bot1(asks).bot2((_, _) -> Direction.NONE)
            .bot1First(true).input(api -> { throw new CancellationException(); }).build();
        assertThrows(CancellationException.class, cancelled::logicTick);
    }

    @Test void inputFailuresPreserveTheirCauseAndAbortTheGame() {
        var cause = new IllegalArgumentException("UI failed");
        Bot asks = (api, _) -> { api.askPoint(); return Direction.NONE; };
        GameEngine game = GameEngine.builder().bot1(asks).bot2((_, _) -> Direction.NONE).bot1First(true)
            .input(api -> { throw new BotInput.Failure("Popup failed", cause); }).build();
        var error = assertThrows(BotInput.Failure.class, game::logicTick);
        assertSame(cause, error.getCause());
    }

    @Test void noneIsZeroDistanceAndMovingListKeepsOldOrder() {
        assertEquals(List.of(Direction.RIGHT, Direction.DOWN, Direction.LEFT, Direction.UP), Direction.MOVING);
        assertEquals(0, Direction.NONE.dx);
        assertEquals(0, Direction.NONE.dy);
        assertEquals(Direction.NONE, Direction.towards(0, 0));
        assertEquals(Direction.LEFT, Direction.towards(-1, 100));
        assertEquals(Direction.DOWN, Direction.towards(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BotInput.Point(-1, 0));
    }

    @Test void noneSurvivesRecordingAndPlayback() {
        Bot waits = (api, _) -> api.tickCount() < 5 ? Direction.NONE : api.tickCount() < 9 ? Direction.RIGHT : Direction.NONE;
        GameEngine original = GameEngine.builder().bot1(waits).bot2((_, _) -> Direction.NONE).record(true).build();
        List<Long> states = new ArrayList<>();
        while (!original.isGameOver()) { states.add(GameEngineTest.state(original)); original.logicTick(); }
        GameEngine playback = original.recording().newGame();
        for (long state : states) { assertEquals(state, GameEngineTest.state(playback)); playback.logicTick(); }
        assertEquals(GameEngineTest.state(original), GameEngineTest.state(playback));
    }

    @Test void uiSnapshotsAreIndependentOfLaterTicks() {
        GameEngine game = GameEngine.builder().bot1((_, _) -> Direction.RIGHT).bot2((_, _) -> Direction.NONE).build();
        BoardState snapshot = BoardState.capture(game.bot(2));
        game.logicTick();
        assertEquals(1, snapshot.players().getFirst().score());
        assertEquals(2, game.score(1));
        int x = game.bot(1).x(), y = game.bot(1).y();
        assertEquals(0, snapshot.grid()[x][y]);
    }
}