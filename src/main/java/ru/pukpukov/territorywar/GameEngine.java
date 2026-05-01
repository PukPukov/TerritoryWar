package ru.pukpukov.territorywar;

import ru.pukpukov.territorywar.api.Bot;
import ru.pukpukov.territorywar.api.BotAPI;
import ru.pukpukov.territorywar.api.Constants;
import ru.pukpukov.territorywar.api.Direction;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GameEngine {
    private static final Logger log = Logger.getLogger(GameEngine.class.getName());
    
    public static final int COLS = Constants.FIELD_SIZE;
    public static final int ROWS = Constants.FIELD_SIZE;
    private static final int MAX_CELLS = COLS * ROWS;
    public static final int MAX_IDLE_TICKS = 128;
    
    public static final int EMPTY = 0;
    public static final int PLAYER_1 = 1;
    public static final int PLAYER_2 = 2;
    
    private final int[][] grid = new int[COLS][ROWS];
    private final BotState bot1;
    private final BotState bot2;
    
    private int score1 = 1;
    private int score2 = 1;
    private int totalClaimedCells = 2;
    private int ticksWithoutClaim = 0;
    private boolean isGameOver = false;
    private boolean bot1GoesFirst;
    
    // Переиспользуемые массивы для Flood Fill
    private final int[] queueX = new int[MAX_CELLS];
    private final int[] queueY = new int[MAX_CELLS];
    private final int[] regionX = new int[MAX_CELLS];
    private final int[] regionY = new int[MAX_CELLS];
    
    private final int[][] visited = new int[COLS][ROWS];
    private int visitToken = 0;
    
    private static final int[] DX = {0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0};
    
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
        
        @Override public int id() { return id; }
        @Override public int x() { return x; }
        @Override public int y() { return y; }
        
        @Override
        public int get(int gx, int gy) {
            return isInBounds(gx, gy) ? grid[gx][gy] : -1;
        }
        
        @Override
        public int next(Direction dir) {
            int nx = x, ny = y;
            if (dir == Direction.UP) ny--;
            else if (dir == Direction.DOWN) ny++;
            else if (dir == Direction.LEFT) nx--;
            else if (dir == Direction.RIGHT) nx++;
            return get(nx, ny);
        }
    }
    
    public GameEngine(Bot logic1, Bot logic2) {
        bot1 = new BotState(PLAYER_1, 10, 10, logic1);
        bot2 = new BotState(PLAYER_2, 39, 39, logic2);
        grid[bot1.x][bot1.y] = PLAYER_1;
        grid[bot2.x][bot2.y] = PLAYER_2;
        
        bot1GoesFirst = ThreadLocalRandom.current().nextBoolean();
    }
    
    public void logicTick() {
        if (isGameOver) return;
        
        int claimedThisTick = 0;
        
        if (bot1GoesFirst) {
            claimedThisTick += applyMove(bot1, getMoveSafe(bot1));
            claimedThisTick += applyMove(bot2, getMoveSafe(bot2));
        } else {
            claimedThisTick += applyMove(bot2, getMoveSafe(bot2));
            claimedThisTick += applyMove(bot1, getMoveSafe(bot1));
        }
        
        bot1GoesFirst = !bot1GoesFirst;
        
        if (claimedThisTick > 0) {
            ticksWithoutClaim = 0;
            totalClaimedCells += claimedThisTick;
        } else {
            ticksWithoutClaim++;
        }
        
        // Проверка окончания игры
        if (ticksWithoutClaim >= MAX_IDLE_TICKS || totalClaimedCells >= MAX_CELLS) {
            isGameOver = true;
            
            // ФЛУД ФИЛЛ ВЫПОЛНЯЕТСЯ ТОЛЬКО ОДИН РАЗ В САМОМ КОНЦЕ
            floodFillAutoClaim();
        }
    }
    
    private Direction getMoveSafe(BotState botState) {
        try {
            return botState.logic.move(botState, botState.memory);
        } catch (Exception e) {
            log.log(Level.WARNING, "Ошибка выполнения кода у Бота " + botState.id, e);
            return null;
        }
    }
    
    private int applyMove(BotState bot, Direction dir) {
        if (dir == null) return 0;
        int nx = bot.x, ny = bot.y;
        
        switch (dir) {
            case UP -> ny--;
            case DOWN -> ny++;
            case LEFT -> nx--;
            case RIGHT -> nx++;
        }
        
        if (isInBounds(nx, ny)) {
            int target = grid[nx][ny];
            if (target == EMPTY || target == bot.id) {
                bot.x = nx;
                bot.y = ny;
                if (target == EMPTY) {
                    grid[nx][ny] = bot.id;
                    if (bot.id == PLAYER_1) score1++; else score2++;
                    return 1;
                }
            }
        }
        return 0;
    }
    
    private void floodFillAutoClaim() {
        visitToken++;
        
        for (int sx = 0; sx < COLS; sx++) {
            for (int sy = 0; sy < ROWS; sy++) {
                if (grid[sx][sy] == EMPTY && visited[sx][sy] != visitToken) {
                    
                    int qHead = 0;
                    int qTail = 0;
                    int rCount = 0;
                    
                    boolean borders1 = false;
                    boolean borders2 = false;
                    
                    queueX[qTail] = sx;
                    queueY[qTail] = sy;
                    qTail++;
                    visited[sx][sy] = visitToken;
                    
                    while (qHead < qTail) {
                        int cx = queueX[qHead];
                        int cy = queueY[qHead];
                        qHead++;
                        
                        regionX[rCount] = cx;
                        regionY[rCount] = cy;
                        rCount++;
                        
                        for (int i = 0; i < 4; i++) {
                            int nx = cx + DX[i];
                            int ny = cy + DY[i];
                            
                            if (isInBounds(nx, ny)) {
                                int cellVal = grid[nx][ny];
                                if (cellVal == EMPTY) {
                                    if (visited[nx][ny] != visitToken) {
                                        visited[nx][ny] = visitToken;
                                        queueX[qTail] = nx;
                                        queueY[qTail] = ny;
                                        qTail++;
                                    }
                                } else if (cellVal == PLAYER_1) {
                                    borders1 = true;
                                } else if (cellVal == PLAYER_2) {
                                    borders2 = true;
                                }
                            }
                        }
                    }
                    
                    if (borders1 ^ borders2) {
                        int winnerId = borders1 ? PLAYER_1 : PLAYER_2;
                        
                        for (int r = 0; r < rCount; r++) {
                            grid[regionX[r]][regionY[r]] = winnerId;
                        }
                        
                        if (winnerId == PLAYER_1) score1 += rCount;
                        else score2 += rCount;
                    }
                }
            }
        }
    }
    
    private boolean isInBounds(int x, int y) {
        return x >= 0 && x < COLS && y >= 0 && y < ROWS;
    }
    
    public int[][] getGrid() { return grid; }
    public boolean isGameOver() { return isGameOver; }
    public int getScore1() { return score1; }
    public int getScore2() { return score2; }
    public int getBot1X() { return bot1.x; }
    public int getBot1Y() { return bot1.y; }
    public int getBot2X() { return bot2.x; }
    public int getBot2Y() { return bot2.y; }
    public int getWinner() {
        if (score1 > score2) return PLAYER_1;
        if (score2 > score1) return PLAYER_2;
        return 0;
    }
}