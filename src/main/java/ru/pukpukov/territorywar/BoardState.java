package ru.pukpukov.territorywar;

import java.util.List;
import ru.pukpukov.territorywar.api.BotAPI;

/** UI-owned snapshot; background simulations never create these. */
record BoardState(int[][] grid, List<Player> players) {
    record Player(int x, int y, int score) { }

    static BoardState capture(BotAPI bot) {
        BotAPI first = bot.id() == GameEngine.PLAYER_1 ? bot : bot.enemy();
        int[][] grid = new int[GameEngine.COLS][GameEngine.ROWS];
        int[] scores = new int[3];
        for (int x = 0; x < grid.length; x++) for (int y = 0; y < grid[x].length; y++) {
            int owner = first.get(x, y);
            grid[x][y] = owner;
            scores[owner]++;
        }
        return new BoardState(grid, List.of(new Player(first.x(), first.y(), scores[1]), new Player(first.enemy().x(), first.enemy().y(), scores[2])));
    }
}
