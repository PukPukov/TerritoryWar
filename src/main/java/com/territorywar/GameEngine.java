package com.territorywar;

import com.territorywar.api.Bot;
import com.territorywar.api.BotAPI;
import com.territorywar.api.Direction;

import java.util.*;

public class GameEngine {
    public static final int COLS = 50;
    public static final int ROWS = 50;
    public static final int MAX_IDLE_TICKS = 32;

    private final int[][] grid = new int[COLS][ROWS];
    private final BotState bot1;
    private final BotState bot2;

    private int score1 = 1;
    private int score2 = 1;
    private int totalClaimedCells = 2;
    private int ticksWithoutClaim = 0;
    private boolean isGameOver = false;

    // Внутренний класс для хранения стейта бота (API передается самому боту)
    private class BotState implements BotAPI {
        final int id;
        int x, y;
        final Bot logic;
        final Map<String, Object> memory = new HashMap<>();

        BotState(int id, int startX, int startY, Bot logic) {
            this.id = id;
            this.x = startX;
            this.y = startY;
            this.logic = logic;
        }

        @Override public int getMyId() { return id; }
        @Override public int getMyX() { return x; }
        @Override public int getMyY() { return y; }
        @Override public Map<String, Object> getMemory() { return memory; }

        @Override
        public int getGrid(int gx, int gy) {
            return isInBounds(gx, gy) ? grid[gx][gy] : -1;
        }

        @Override
        public int getNext(Direction dir) {
            int nx = x, ny = y;
            if (dir == Direction.UP) ny--;
            else if (dir == Direction.DOWN) ny++;
            else if (dir == Direction.LEFT) nx--;
            else if (dir == Direction.RIGHT) nx++;
            return getGrid(nx, ny);
        }
    }

    public GameEngine(Bot logic1, Bot logic2) {
        bot1 = new BotState(1, 10, 10, logic1);
        bot2 = new BotState(2, 39, 39, logic2);
        grid[bot1.x][bot1.y] = 1;
        grid[bot2.x][bot2.y] = 2;
    }

    public void logicTick() {
        if (isGameOver) return;

        int claimedThisTick = 0;

        // Ходы ботов
        claimedThisTick += applyMove(bot1, getMoveSafe(bot1));
        claimedThisTick += applyMove(bot2, getMoveSafe(bot2));

        // Автозахват территорий (Flood Fill)
        claimedThisTick += floodFillAutoClaim();

        if (claimedThisTick > 0) {
            ticksWithoutClaim = 0;
            totalClaimedCells += claimedThisTick;
        } else {
            ticksWithoutClaim++;
        }

        if (ticksWithoutClaim >= MAX_IDLE_TICKS || totalClaimedCells >= COLS * ROWS) {
            isGameOver = true;
        }
    }

    private Direction getMoveSafe(BotState botState) {
        try {
            return botState.logic.move(botState);
        } catch (Exception e) {
            return null; // В случае ошибки кода бота он просто пропускает ход
        }
    }

    private int applyMove(BotState bot, Direction dir) {
        if (dir == null) return 0;
        int nx = bot.x, ny = bot.y;
        if (dir == Direction.UP) ny--;
        else if (dir == Direction.DOWN) ny++;
        else if (dir == Direction.LEFT) nx--;
        else if (dir == Direction.RIGHT) nx++;

        if (isInBounds(nx, ny)) {
            int target = grid[nx][ny];
            if (target == 0 || target == bot.id) {
                bot.x = nx;
                bot.y = ny;
                if (target == 0) {
                    grid[nx][ny] = bot.id;
                    if (bot.id == 1) score1++; else score2++;
                    return 1;
                }
            }
        }
        return 0;
    }

    private int floodFillAutoClaim() {
        boolean[][] visited = new boolean[COLS][ROWS];
        int cellsClaimed = 0;

        for (int sx = 0; sx < COLS; sx++) {
            for (int sy = 0; sy < ROWS; sy++) {
                if (grid[sx][sy] == 0 && !visited[sx][sy]) {
                    List<int[]> regionCells = new ArrayList<>();
                    Set<Integer> borderingPlayers = new HashSet<>();
                    Queue<int[]> queue = new LinkedList<>();

                    queue.add(new int[]{sx, sy});
                    visited[sx][sy] = true;

                    while (!queue.isEmpty()) {
                        int[] curr = queue.poll();
                        regionCells.add(curr);

                        int[][] neighbors = {
                            {curr[0], curr[1] - 1}, {curr[0], curr[1] + 1},
                            {curr[0] - 1, curr[1]}, {curr[0] + 1, curr[1]}
                        };

                        for (int[] n : neighbors) {
                            if (isInBounds(n[0], n[1])) {
                                int cellVal = grid[n[0]][n[1]];
                                if (cellVal == 0) {
                                    if (!visited[n[0]][n[1]]) {
                                        visited[n[0]][n[1]] = true;
                                        queue.add(n);
                                    }
                                } else {
                                    borderingPlayers.add(cellVal);
                                }
                            }
                        }
                    }

                    // Если область окружена только одним игроком
                    if (borderingPlayers.size() == 1) {
                        int winnerId = borderingPlayers.iterator().next();
                        for (int[] cell : regionCells) {
                            grid[cell[0]][cell[1]] = winnerId;
                        }
                        if (winnerId == 1) score1 += regionCells.size();
                        else score2 += regionCells.size();
                        cellsClaimed += regionCells.size();
                    }
                }
            }
        }
        return cellsClaimed;
    }

    private boolean isInBounds(int x, int y) {
        return x >= 0 && x < COLS && y >= 0 && y < ROWS;
    }

    // Getters
    public int[][] getGrid() { return grid; }
    public boolean isGameOver() { return isGameOver; }
    public int getScore1() { return score1; }
    public int getScore2() { return score2; }
    public int getBot1X() { return bot1.x; }
    public int getBot1Y() { return bot1.y; }
    public int getBot2X() { return bot2.x; }
    public int getBot2Y() { return bot2.y; }
    public int getWinner() {
        if (score1 > score2) return 1;
        if (score2 > score1) return 2;
        return 0;
    }
}