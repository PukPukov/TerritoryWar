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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

public class TerritoryWarApp extends Application {
    
    public static final int SIM_COUNT = 1000;
    
    private static final Logger log = Logger.getLogger(TerritoryWarApp.class.getName());
    
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
    
    private final Color COLOR_BOT1 = Color.web("#FF5A5A");
    private final Color COLOR_TERR1 = Color.web("#FFBDBD");
    private final Color COLOR_BOT2 = Color.web("#5A82FF");
    private final Color COLOR_TERR2 = Color.web("#BDCFFF");
    private final Color COLOR_GRID = Color.web("#f5f5f5");
    
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Territory War");
        
        VBox gamePanel = new VBox(10);
        gamePanel.setAlignment(Pos.CENTER);
        gamePanel.setPadding(new Insets(10));
        
        HBox scoreBoard = new HBox(50);
        scoreBoard.setAlignment(Pos.CENTER);
        score1Label = createScoreLabel("Бот 1: 1", COLOR_BOT1);
        score2Label = createScoreLabel("Бот 2: 1", COLOR_BOT2);
        statusLabel = new Label("Ожидание...");
        scoreBoard.getChildren().addAll(score1Label, statusLabel, score2Label);
        
        canvas = new Canvas(GameEngine.COLS * CELL_SIZE, GameEngine.ROWS * CELL_SIZE);
        gc = canvas.getGraphicsContext2D();
        
        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER);
        Button btnStart = new Button("Старт");
        Button btnPause = new Button("Пауза");
        Button btnReset = new Button("Сброс");
        speedSlider = new Slider(1, 150, 130);
        speedSlider.setPrefWidth(100);
        Button btnSim = new Button(SIM_COUNT+" симуляций");
        btnSim.setStyle("-fx-base: #9b59b6;");
        
        controls.getChildren().addAll(btnStart, btnPause, btnReset, new Label("Скорость:"), speedSlider, btnSim);
        gamePanel.getChildren().addAll(scoreBoard, canvas, controls);
        
        VBox editorsPanel = new VBox(10);
        editorsPanel.setPadding(new Insets(10));
        editorsPanel.setPrefWidth(450);
        
        bot1CodeArea = createCodeArea(getBot1DefaultCode());
        bot2CodeArea = createCodeArea(getBot2DefaultCode());
        
        Label lblBot1 = new Label("Код Бота 1 (Красный)");
        lblBot1.setTextFill(COLOR_BOT1);
        Label lblBot2 = new Label("Код Бота 2 (Синий)");
        lblBot2.setTextFill(COLOR_BOT2);
        
        editorsPanel.getChildren().addAll(lblBot1, bot1CodeArea, lblBot2, bot2CodeArea);
        
        HBox root = new HBox(10, gamePanel, editorsPanel);
        root.setPadding(new Insets(15));
        root.setStyle("-fx-background-color: #EFECE6;");
        
        btnStart.setOnAction(e -> startGame());
        btnPause.setOnAction(e -> pauseGame());
        btnReset.setOnAction(e -> resetGame());
        btnSim.setOnAction(e -> runParallelSimulations(primaryStage));
        
        setupGameLoop();
        
        primaryStage.setScene(new Scene(root));
        primaryStage.show();
        drawEmptyGrid();
    }
    
    private Label createScoreLabel(String text, Color color) {
        Label label = new Label(text);
        label.setTextFill(color);
        label.setFont(Font.font("System", 16));
        return label;
    }
    
    private TextArea createCodeArea(String defaultCode) {
        TextArea area = new TextArea(defaultCode);
        area.setFont(Font.font("Monospaced", 13));
        VBox.setVgrow(area, Priority.ALWAYS);
        return area;
    }
    
    private void setupGameLoop() {
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
    }
    
    private boolean compileBots() {
        try {
            currentBot1Class = BotCompiler.compileBot(bot1CodeArea.getText());
            currentBot2Class = BotCompiler.compileBot(bot2CodeArea.getText());
            return true;
        } catch (Exception ex) {
            log.log(Level.SEVERE, "Сбой компиляции", ex);
            showErrorDialog("Ошибка компиляции", ex.getMessage());
            return false;
        }
    }
    
    private void showErrorDialog(String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText(header);
        
        TextArea textArea = new TextArea(content);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setMaxWidth(Double.MAX_VALUE);
        textArea.setMaxHeight(Double.MAX_VALUE);
        
        alert.getDialogPane().setExpandableContent(textArea);
        alert.getDialogPane().setExpanded(true);
        alert.show();
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
                log.log(Level.SEVERE, "Не удалось инстанцировать ботов", e);
                return;
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
                if (grid[x][y] == GameEngine.PLAYER_1) {
                    gc.setFill(COLOR_TERR1);
                    gc.fillRect(x * CELL_SIZE, y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
                } else if (grid[x][y] == GameEngine.PLAYER_2) {
                    gc.setFill(COLOR_TERR2);
                    gc.fillRect(x * CELL_SIZE, y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
                }
            }
        }
        
        gc.setFill(COLOR_BOT1);
        gc.fillRect(visualGame.getBot1X() * CELL_SIZE, visualGame.getBot1Y() * CELL_SIZE, CELL_SIZE, CELL_SIZE);
        
        gc.setFill(COLOR_BOT2);
        gc.fillRect(visualGame.getBot2X() * CELL_SIZE, visualGame.getBot2Y() * CELL_SIZE, CELL_SIZE, CELL_SIZE);
    }
    
    private void updateScoreUI() {
        if (visualGame != null) {
            score1Label.setText("Бот 1: " + visualGame.getScore1());
            score2Label.setText("Бот 2: " + visualGame.getScore2());
        }
    }
    
    private void runParallelSimulations(Stage parentWindow) {
        pauseGame();
        if (!compileBots()) return;
        
        Stage simStage = new Stage();
        simStage.initOwner(parentWindow);
        simStage.initModality(Modality.APPLICATION_MODAL);
        simStage.setTitle("Симуляция "+SIM_COUNT+" игр");
        
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        vbox.setAlignment(Pos.CENTER);
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(250);
        vbox.getChildren().addAll(new Label("Симуляция в процессе..."), progressBar);
        
        simStage.setScene(new Scene(vbox, 300, 100));
        simStage.show();
        
        AtomicInteger progress = new AtomicInteger(0);
        int totalSims = SIM_COUNT;
        
        Thread simThread = new Thread(() -> {
            long[] results = IntStream.range(0, totalSims)
                .parallel()
                .mapToLong(i -> {
                    try {
                        GameEngine engine = new GameEngine(
                            currentBot1Class.getDeclaredConstructor().newInstance(),
                            currentBot2Class.getDeclaredConstructor().newInstance()
                        );
                        
                        int failsafe = 3000;
                        while (!engine.isGameOver() && failsafe > 0) {
                            engine.logicTick();
                            failsafe--;
                        }
                        
                        int finishedCount = progress.incrementAndGet();
                        Platform.runLater(() -> progressBar.setProgress((double) finishedCount / totalSims));
                        
                        return engine.getWinner();
                    } catch (Exception e) {
                        log.log(Level.SEVERE, "Сбой во время симуляции", e);
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
        alert.setTitle("Результаты "+SIM_COUNT+" симуляций");
        alert.setHeaderText(null);
        alert.setContentText(String.format("Бот 1 (Красный) победы: %d\nБот 2 (Синий) победы: %d\nНичьи: %d", w1, w2, draws));
        alert.show();
    }
    
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