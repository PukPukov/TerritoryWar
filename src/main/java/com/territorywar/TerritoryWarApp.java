package com.territorywar;

import com.territorywar.api.Bot;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

public class TerritoryWarApp extends Application {

    private Canvas canvas;
    private GraphicsContext gc;
    private Label score1Label, score2Label, statusLabel;
    private TextArea bot1CodeArea, bot2CodeArea;
    private Slider speedSlider;

    private GameEngine visualGame;
    private AnimationTimer gameLoop;
    private long lastTickTime = 0;

    private Class<? extends Bot> currentBot1Class;
    private Class<? extends Bot> currentBot2Class;

    private static final int CELL_SIZE = 10;
    
    // Цвета
    private final Color COLOR_BOT1 = Color.web("#FF5A5A");
    private final Color COLOR_TERR1 = Color.web("#FFBDBD");
    private final Color COLOR_BOT2 = Color.web("#5A82FF");
    private final Color COLOR_TERR2 = Color.web("#BDCFFF");
    private final Color COLOR_GRID = Color.web("#f5f5f5");

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Territory War - Java 21 Parallel Simulation");

        // Левая панель (Игра и кнопки)
        VBox gamePanel = new VBox(10);
        gamePanel.setAlignment(Pos.CENTER);
        gamePanel.setPadding(new Insets(10));

        HBox scoreBoard = new HBox(50);
        scoreBoard.setAlignment(Pos.CENTER);
        score1Label = new Label("Бот 1: 1");
        score1Label.setTextFill(COLOR_BOT1);
        score1Label.setFont(Font.font("System", 16));
        score2Label = new Label("Бот 2: 1");
        score2Label.setTextFill(COLOR_BOT2);
        score2Label.setFont(Font.font("System", 16));
        statusLabel = new Label("Ожидание...");
        scoreBoard.getChildren().addAll(score1Label, statusLabel, score2Label);

        canvas = new Canvas(GameEngine.COLS * CELL_SIZE, GameEngine.ROWS * CELL_SIZE);
        gc = canvas.getGraphicsContext2D();

        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER);
        Button btnStart = new Button("▶ Старт");
        Button btnPause = new Button("⏸ Пауза");
        Button btnReset = new Button("🔄 Сброс");
        speedSlider = new Slider(1, 150, 130);
        speedSlider.setPrefWidth(100);
        Label speedLabel = new Label("Скорость:");
        Button btnSim = new Button("🚀 100 Симуляций");
        btnSim.setStyle("-fx-base: #9b59b6;");

        controls.getChildren().addAll(btnStart, btnPause, btnReset, speedLabel, speedSlider, btnSim);
        gamePanel.getChildren().addAll(scoreBoard, canvas, controls);

        // Правая панель (Код)
        VBox editorsPanel = new VBox(10);
        editorsPanel.setPadding(new Insets(10));
        editorsPanel.setPrefWidth(400);

        Label lblBot1 = new Label("Код Бота 1 (Спираль)");
        lblBot1.setTextFill(COLOR_BOT1);
        bot1CodeArea = new TextArea(getBot1DefaultCode());
        bot1CodeArea.setFont(Font.font("Monospaced", 13));
        VBox.setVgrow(bot1CodeArea, Priority.ALWAYS);

        Label lblBot2 = new Label("Код Бота 2 (Искатель)");
        lblBot2.setTextFill(COLOR_BOT2);
        bot2CodeArea = new TextArea(getBot2DefaultCode());
        bot2CodeArea.setFont(Font.font("Monospaced", 13));
        VBox.setVgrow(bot2CodeArea, Priority.ALWAYS);

        editorsPanel.getChildren().addAll(lblBot1, bot1CodeArea, lblBot2, bot2CodeArea);

        HBox root = new HBox(10, gamePanel, editorsPanel);
        root.setPadding(new Insets(15));
        root.setStyle("-fx-background-color: #EFECE6;");

        // Обработчики кнопок
        btnStart.setOnAction(e -> startGame());
        btnPause.setOnAction(e -> pauseGame());
        btnReset.setOnAction(e -> resetGame());
        btnSim.setOnAction(e -> runParallelSimulations(primaryStage));

        // Игровой цикл для визуального отображения
        gameLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                long delayNs = (151 - (long)speedSlider.getValue()) * 1_000_000L;
                if (now - lastTickTime > delayNs) {
                    if (visualGame != null && !visualGame.isGameOver()) {
                        visualGame.logicTick();
                        drawGame();
                        updateScoreUI();
                        if (visualGame.isGameOver()) {
                            pauseGame();
                            int winner = visualGame.getWinner();
                            statusLabel.setText(winner == 0 ? "Ничья!" : "Победил Бот " + winner);
                        }
                    }
                    lastTickTime = now;
                }
            }
        };

        primaryStage.setScene(new Scene(root));
        primaryStage.show();
        
        drawEmptyGrid();
    }

    private boolean compileBots() {
        try {
            currentBot1Class = BotCompiler.compileBot(bot1CodeArea.getText());
            currentBot2Class = BotCompiler.compileBot(bot2CodeArea.getText());
            return true;
        } catch (Exception ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR, ex.getMessage(), ButtonType.OK);
            alert.setHeaderText("Ошибка компиляции кода ботов");
            alert.show();
            return false;
        }
    }

    private void startGame() {
        if (visualGame == null || visualGame.isGameOver()) {
            if (!compileBots()) return;
            try {
                visualGame = new GameEngine(
                    currentBot1Class.getDeclaredConstructor().newInstance(),
                    currentBot2Class.getDeclaredConstructor().newInstance()
                );
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        statusLabel.setText("Игра идет...");
        gameLoop.start();
    }

    private void pauseGame() {
        gameLoop.stop();
        if (visualGame != null && !visualGame.isGameOver()) {
            statusLabel.setText("Пауза");
        }
    }

    private void resetGame() {
        pauseGame();
        visualGame = null;
        statusLabel.setText("Ожидание...");
        score1Label.setText("Бот 1: 1");
        score2Label.setText("Бот 2: 1");
        drawEmptyGrid();
    }

    private void drawEmptyGrid() {
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        gc.setStroke(COLOR_GRID);
        for (int i = 0; i <= GameEngine.COLS; i++) {
            gc.strokeLine(i * CELL_SIZE, 0, i * CELL_SIZE, canvas.getHeight());
            gc.strokeLine(0, i * CELL_SIZE, canvas.getWidth(), i * CELL_SIZE);
        }
    }

    private void drawGame() {
        drawEmptyGrid();
        if (visualGame == null) return;

        int[][] grid = visualGame.getGrid();
        for (int x = 0; x < GameEngine.COLS; x++) {
            for (int y = 0; y < GameEngine.ROWS; y++) {
                if (grid[x][y] == 1) {
                    gc.setFill(COLOR_TERR1);
                    gc.fillRect(x * CELL_SIZE, y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
                } else if (grid[x][y] == 2) {
                    gc.setFill(COLOR_TERR2);
                    gc.fillRect(x * CELL_SIZE, y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
                }
            }
        }

        // Бот 1
        gc.setFill(COLOR_BOT1);
        gc.fillRect(visualGame.getBot1X() * CELL_SIZE, visualGame.getBot1Y() * CELL_SIZE, CELL_SIZE, CELL_SIZE);
        // Бот 2
        gc.setFill(COLOR_BOT2);
        gc.fillRect(visualGame.getBot2X() * CELL_SIZE, visualGame.getBot2Y() * CELL_SIZE, CELL_SIZE, CELL_SIZE);
    }

    private void updateScoreUI() {
        if (visualGame != null) {
            score1Label.setText("Бот 1: " + visualGame.getScore1());
            score2Label.setText("Бот 2: " + visualGame.getScore2());
        }
    }

    // ==========================================
    // ЛОГИКА ПАРАЛЛЕЛЬНОЙ СИМУЛЯЦИИ 100 ИГР
    // ==========================================
    private void runParallelSimulations(Stage parentWindow) {
        pauseGame();
        if (!compileBots()) return;

        // Создаем UI для прогресса
        Stage simStage = new Stage();
        simStage.initOwner(parentWindow);
        simStage.initModality(Modality.APPLICATION_MODAL);
        simStage.setTitle("Симуляция 100 игр");

        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        vbox.setAlignment(Pos.CENTER);
        Label lblProgress = new Label("Симуляция в процессе...");
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(250);
        vbox.getChildren().addAll(lblProgress, progressBar);

        simStage.setScene(new Scene(vbox, 300, 100));
        simStage.show();

        AtomicInteger progress = new AtomicInteger(0);
        int totalSims = 100;

        // Запускаем в отдельном потоке, чтобы не блочить UI
        Thread simThread = new Thread(() -> {
            // Parallel stream для многопоточности - не требует синхронизации
            // так как каждый GameEngine полностью изолирован
            long[] results = IntStream.range(0, totalSims)
                    .parallel()
                    .mapToLong(i -> {
                        try {
                            Bot b1 = currentBot1Class.getDeclaredConstructor().newInstance();
                            Bot b2 = currentBot2Class.getDeclaredConstructor().newInstance();
                            GameEngine engine = new GameEngine(b1, b2);

                            int failsafe = 3000;
                            while (!engine.isGameOver() && failsafe > 0) {
                                engine.logicTick();
                                failsafe--;
                            }

                            int finishedCount = progress.incrementAndGet();
                            // Обновляем UI безопасно
                            Platform.runLater(() -> progressBar.setProgress((double) finishedCount / totalSims));

                            return engine.getWinner();
                        } catch (Exception e) {
                            e.printStackTrace();
                            return -1;
                        }
                    })
                    .toArray();

            int w1 = 0, w2 = 0, draws = 0;
            for (long r : results) {
                if (r == 1) w1++;
                else if (r == 2) w2++;
                else if (r == 0) draws++;
            }

            final int fw1 = w1, fw2 = w2, fdraws = draws;
            Platform.runLater(() -> {
                simStage.close();
                showSimResults(fw1, fw2, fdraws);
            });
        });
        simThread.setDaemon(true);
        simThread.start();
    }

    private void showSimResults(int w1, int w2, int draws) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Результаты 100 симуляций");
        alert.setHeaderText(null);
        alert.setContentText(String.format("Бот 1 (Красный) победы: %d\nБот 2 (Синий) победы: %d\nНичьи: %d", w1, w2, draws));
        alert.show();
    }

    // ==========================================
    // Код ботов по умолчанию (переведен на Java)
    // ==========================================
    private String getBot1DefaultCode() {
        return """
            Direction[] dirs = {Direction.UP, Direction.RIGHT, Direction.DOWN, Direction.LEFT};
            Map<String, Object> mem = api.getMemory();
            
            if (!mem.containsKey("dirIndex")) mem.put("dirIndex", 1);
            int dirIndex = (int) mem.get("dirIndex");
            
            Direction currDir = dirs[dirIndex];
            
            if (api.getNext(currDir) != 0) {
                dirIndex = (dirIndex + 1) % 4;
                mem.put("dirIndex", dirIndex);
                currDir = dirs[dirIndex];
            }
            
            if (api.getNext(currDir) != 0) {
                for (Direction d : dirs) {
                    if (api.getNext(d) == api.getMyId()) return d; 
                }
            }
            return currDir;
            """;
    }

    private String getBot2DefaultCode() {
        return """
            List<Direction> dirs = new ArrayList<>(Arrays.asList(Direction.values()));
            Collections.shuffle(dirs);
            
            for (Direction dir : dirs) {
                if (api.getNext(dir) == 0) return dir;
            }
            
            for (Direction dir : dirs) {
                if (api.getNext(dir) == api.getMyId()) return dir;
            }
            return Direction.UP;
            """;
    }

    public static void main(String[] args) {
        launch(args);
    }
}