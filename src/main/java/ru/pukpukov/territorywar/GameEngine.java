package ru.pukpukov.territorywar;

import module java.base;
import java.util.logging.*;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import ru.pukpukov.territorywar.api.*;

import static ru.pukpukov.territorywar.api.Constants.FIELD_SIZE;

import static ru.pukpukov.territorywar.api.Util.fnnul;

public final class GameEngine {
    public static final int COLS = FIELD_SIZE, ROWS = FIELD_SIZE;
    public static final int EMPTY = 0, PLAYER_1 = 1, PLAYER_2 = 2;
    public static final int MAX_IDLE_TICKS = 128;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Logger LOG = Logger.getLogger(GameEngine.class.getName());

    private final @Getter int[][] grid = new int[COLS][ROWS];
    private final BotState[] bots;
    private final boolean initialOrder;
    private final @Nullable ByteArrayOutputStream moves;
    private int first, idleTicks;
    private @Getter boolean isGameOver;
    
    @Getter
    private final class BotState implements BotAPI {
        final int id;
        final Bot logic;
        final Map<String, Object> memory = new HashMap<>();
        int x, y, tickCount, score = 1;

        BotState(int id, int start, Bot logic) {
            this.id = id;
            this.x = this.y = start;
            this.logic = logic;
        }

        @Override public BotAPI enemy() { return bots[2 - id]; }
        @Override public int get(int x, int y) { return inBounds(x, y) ? grid[x][y] : -1; }
    }

    // Fixed initial order is also used by playback and parity tests.
    @Builder
    public GameEngine(@NonNull Bot bot1, @NonNull Bot bot2, @Nullable Boolean bot1First, @Nullable Boolean record) {
        if (bot1First == null) bot1First = ThreadLocalRandom.current().nextBoolean();
        if (record == null) record = false;
        int start = FIELD_SIZE * 15 / 100;
        bots = new BotState[]{new BotState(PLAYER_1, start, bot1), new BotState(PLAYER_2, FIELD_SIZE - start - 1, bot2)};
        for (BotState bot : bots) grid[bot.x][bot.y] = bot.id;
        initialOrder = bot1First;
        first = bot1First ? 0 : 1;
        moves = record ? new ByteArrayOutputStream() : null;
    }

    public void logicTick() {
        if (isGameOver) return;
        int claimed = takeTurn(bots[first]) + takeTurn(bots[1 - first]);
        first = 1 - first;
        idleTicks = claimed > 0 ? 0 : idleTicks + 1;
        if (idleTicks >= MAX_IDLE_TICKS || bots[0].score + bots[1].score == COLS * ROWS) {
            isGameOver = true;
            floodFillAutoClaim();
        }
    }

    private int takeTurn(BotState bot) {
        Direction direction;
        try {
            direction = bot.logic.move(bot, bot.memory);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Ошибка выполнения кода у Бота " + bot.id, e);
            direction = null;
        }
        if (moves != null) moves.write(direction == null ? 0 : direction.ordinal() + 1);
        bot.tickCount++;
        if (direction == null) return 0;
        int x = bot.nextX(direction), y = bot.nextY(direction);
        int target = bot.get(x, y);
        if (target != EMPTY && target != bot.id) return 0;
        bot.x = x;
        bot.y = y;
        if (target != EMPTY) return 0;
        grid[x][y] = bot.id;
        bot.score++;
        return 1;
    }

    // Only called at game end. The BFS queue is also the list of region cells.
    private void floodFillAutoClaim() {
        boolean[][] visited = new boolean[COLS][ROWS];
        int[] queue = new int[COLS * ROWS];
        for (int sx = 0; sx < COLS; sx++) for (int sy = 0; sy < ROWS; sy++) {
            if (grid[sx][sy] != EMPTY || visited[sx][sy]) continue;
            int head = 0, tail = 0, borders = 0;
            queue[tail++] = sx * ROWS + sy;
            visited[sx][sy] = true;
            while (head < tail) {
                int cell = queue[head++];
                for (Direction direction : DIRECTIONS) {
                    int x = cell / ROWS + direction.dx, y = cell % ROWS + direction.dy;
                    if (!inBounds(x, y)) continue;
                    int owner = grid[x][y];
                    if (owner != EMPTY) borders |= 1 << owner;
                    else if (!visited[x][y]) {
                        visited[x][y] = true;
                        assert tail < queue.length : "Region queue overflow";
                        queue[tail++] = x * ROWS + y;
                    }
                }
            }
            if (Integer.bitCount(borders) != 1) continue;
            int owner = Integer.numberOfTrailingZeros(borders);
            for (int i = 0; i < tail; i++) grid[queue[i] / ROWS][queue[i] % ROWS] = owner;
            bots[owner - 1].score += tail;
        }
    }

    private static boolean inBounds(int x, int y) { return x >= 0 && x < COLS && y >= 0 && y < ROWS; }
    public BotAPI bot(int id) { return bots[id - 1]; }
    public int score(int id) { return bots[id - 1].score; }
    public int winner() { return bots[0].score == bots[1].score ? EMPTY : bots[0].score > bots[1].score ? PLAYER_1 : PLAYER_2; }

    public GameReplay recording() {
        assert isGameOver && moves != null : "Only completed search games have a recording";
        return new GameReplay(initialOrder, fnnul(moves).toByteArray());
    }
}
