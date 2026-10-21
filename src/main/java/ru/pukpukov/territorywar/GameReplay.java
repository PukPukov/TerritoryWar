package ru.pukpukov.territorywar;

import java.io.ByteArrayInputStream;
import ru.pukpukov.territorywar.api.*;

/** Actual moves, not random seeds: replay never runs the original bot algorithms. */
public final class GameReplay {
    private static final Direction[] DIRECTIONS = Direction.values();
    private final boolean bot1First;
    private final byte[] moves;
    private final int score1, score2;

    GameReplay(boolean bot1First, byte[] moves, int score1, int score2) {
        assert moves.length > 0 && moves.length % 2 == 0 : "Incomplete recording";
        this.score1 = score1;
        this.score2 = score2;
        this.bot1First = bot1First;
        this.moves = moves.clone();
    }

    public int score(int player) {
        assert player == GameEngine.PLAYER_1 || player == GameEngine.PLAYER_2;
        return player == GameEngine.PLAYER_1 ? score1 : score2;
    }

    public GameEngine newGame() {
        var input = new ByteArrayInputStream(moves);
        Bot playback = (_, _) -> {
            int move = input.read();
            assert move >= 0 && move <= DIRECTIONS.length : "Recording exhausted or corrupt";
            return move == 0 ? null : DIRECTIONS[move - 1];
        };
        return GameEngine.builder().bot1(playback).bot2(playback).bot1First(bot1First).record(false).build();
    }
}
