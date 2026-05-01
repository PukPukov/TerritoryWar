package ru.pukpukov.territorywar;

import ru.pukpukov.territorywar.api.Bot;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
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
    private static final Logger log = Logger.getLogger(TerritoryWarApp.class.getName());
    
    private static final int SIM_COUNT = 10000;
    
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
    
    private double cellSize = 10;
    
    private final Color COLOR_BOT1 = Color.web("#FF5A5A");
    private final Color COLOR_TERR1 = Color.web("#FFBDBD");
    private final Color COLOR_BOT2 = Color.web("#5A82FF");
    private final Color COLOR_TERR2 = Color.web("#BDCFFF");
    private final Color COLOR_GRID = Color.web("#e0e0e0");
    
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Territory War");
        
        // --- ЛЕВАЯ ПАНЕЛЬ (ИГРА) ---
        VBox gamePanel = new VBox(15);
        gamePanel.setAlignment(Pos.CENTER);
        gamePanel.setPadding(new Insets(10));
        gamePanel.setStyle("-fx-background-color: #FAFAFA;");
        
        HBox scoreBoard = new HBox(40);
        scoreBoard.setAlignment(Pos.CENTER);
        score1Label = createScoreLabel("Бот 1: 1", COLOR_BOT1);
        score2Label = createScoreLabel("Бот 2: 1", COLOR_BOT2);
        statusLabel = new Label("Ожидание...");
        statusLabel.setFont(Font.font("System", 16));
        scoreBoard.getChildren().addAll(score1Label, statusLabel, score2Label);
        
        canvas = new Canvas(0, 0);
        gc = canvas.getGraphicsContext2D();
        StackPane canvasContainer = new StackPane(canvas);
        canvasContainer.setStyle("-fx-border-color: #ccc; -fx-border-width: 2px; -fx-background-color: white;");
        VBox.setVgrow(canvasContainer, Priority.ALWAYS);
        
        canvasContainer.widthProperty().addListener((obs, oldVal, newVal) -> resizeCanvas(canvasContainer));
        canvasContainer.heightProperty().addListener((obs, oldVal, newVal) -> resizeCanvas(canvasContainer));
        
        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER);
        Button btnStart = new Button("▶ Старт");
        Button btnPause = new Button("Пауза");
        Button btnReset = new Button("Сброс");
        speedSlider = new Slider(0, 100, 50);
        speedSlider.setPrefWidth(120);
        
        Button btnSim = new Button(SIM_COUNT + " симуляций");
        btnSim.setStyle("-fx-base: #9b59b6; -fx-text-fill: white;");
        
        controls.getChildren().addAll(btnStart, btnPause, btnReset, new Label("Скорость:"), speedSlider, btnSim);
        gamePanel.getChildren().addAll(scoreBoard, canvasContainer, controls);
        
        // --- ПРАВАЯ ПАНЕЛЬ (ПРАВИЛА И КОД) ---
        VBox editorsPanel = new VBox(10);
        editorsPanel.setPadding(new Insets(10));
        
        TitledPane rulesPane = createRulesPane();
        
        // Создаем пустые поля, текст подтянется из файлов
        bot1CodeArea = createCodeArea("");
        bot2CodeArea = createCodeArea("");
        
        VBox box1 = new VBox(5, createScoreLabel("Код Бота 1 (Красный)", COLOR_BOT1), bot1CodeArea);
        VBox box2 = new VBox(5, createScoreLabel("Код Бота 2 (Синий)", COLOR_BOT2), bot2CodeArea);
        
        VBox.setVgrow(box1, Priority.ALWAYS);
        VBox.setVgrow(box2, Priority.ALWAYS);
        
        SplitPane codeSplit = new SplitPane(box1, box2);
        codeSplit.setOrientation(Orientation.VERTICAL);
        VBox.setVgrow(codeSplit, Priority.ALWAYS);
        
        editorsPanel.getChildren().addAll(rulesPane, codeSplit);
        
        // --- ГЛАВНЫЙ SPLIT PANE ---
        SplitPane mainSplit = new SplitPane(gamePanel, editorsPanel);
        mainSplit.setDividerPositions(0.55);
        
        btnStart.setOnAction(e -> startGame());
        btnPause.setOnAction(e -> pauseGame());
        btnReset.setOnAction(e -> resetGame());
        btnSim.setOnAction(e -> runParallelSimulations(primaryStage));
        
        setupGameLoop();
        
        // --- АВТОСИНХРОНИЗАЦИЯ КОДА С ДИСКОМ ---
        CodeSyncManager syncManager = new CodeSyncManager(bot1CodeArea, bot2CodeArea);
        syncManager.init(getBot1DefaultCode(), getBot2DefaultCode());
        
        Scene scene = new Scene(mainSplit, 1100, 700);
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(500);
        primaryStage.show();
    }
    
    private void resizeCanvas(Pane container) {
        double w = container.getWidth() - 10;
        double h = container.getHeight() - 10;
        if (w <= 0 || h <= 0) return;
        
        cellSize = Math.floor(Math.min(w / GameEngine.COLS, h / GameEngine.ROWS));
        canvas.setWidth(cellSize * GameEngine.COLS);
        canvas.setHeight(cellSize * GameEngine.ROWS);
        drawGame();
    }
    
    private Label createScoreLabel(String text, Color color) {
        Label label = new Label(text);
        label.setTextFill(color);
        label.setFont(Font.font("System", 16));
        return label;
    }
    
    private TextArea createCodeArea(String text) {
        TextArea area = new TextArea(text);
        area.setFont(Font.font("Monospaced", 14));
        VBox.setVgrow(area, Priority.ALWAYS);
        return area;
    }
    
    private TitledPane createRulesPane() {
        TextArea rulesText = new TextArea("""
            ПРАВИЛА ИГРЫ
            Цель — захватить как можно больше клеток.
            Клетка захватывается при входе на неё, каждый ход бот может передвинуться в одном из направлений. Замкнутые области закрашиваются автоматически. Нельзя наступать на захваченные врагом территории.
            
            API
            Ваш ход должен вернуть одно из направлений: Direction.UP, DOWN, LEFT, RIGHT. Если бот врезается в чужую территорию или выдает Exception, он пропускает ход!
            
            Код в поле ввода является реализацией метода public Direction move(BotAPI api, Map<String, Object> mem).
            
            Доступные методы BotAPI:
            • api.tickCount() - возвращает номер тика (0, 1, 2, 3, 4...).
            • api.id() - возвращает ваш ID (1 или 2).
            • api.x() / api.y() - ваши текущие координаты.
            • api.get(x, y) - значение клетки: 0 (пусто), 1 (Красный), 2 (Синий), -1 (край карты).
            • api.nextX(Direction dir) - возвращает x-координату клетки, находящейся по направлению dir.
            • api.nextY(Direction dir) - возвращает y-координату клетки, находящейся по направлению dir.
            • api.next(Direction dir) - утилитарный метод, возвращает значение клетки, находящейся по направлению dir.
            • api.canMoveTo(Direction dir) - утилитарный метод для проверки, можно ли сходить в данную клетку.
            
            Статически доступные утилиты и константы:
            - toList(arr)
            - FIELD_SIZE
            """);
        rulesText.setEditable(false);
        rulesText.setWrapText(true);
        rulesText.setPrefRowCount(9);
        rulesText.setStyle("-fx-control-inner-background: #fdfdfd; -fx-font-family: 'Segoe UI', Arial;");
        
        TitledPane pane = new TitledPane("Правила и API (нажмите, чтобы свернуть/развернуть)", rulesText);
        pane.setExpanded(true);
        return pane;
    }
    
    private void setupGameLoop() {
        gameLoop = new AnimationTimer() {
            private long lastUpdate = 0;
            private long accumulatedTime = 0;
            
            @Override
            public void handle(long now) {
                if (lastUpdate == 0) {
                    lastUpdate = now;
                    return;
                }
                
                long elapsedNs = now - lastUpdate;
                lastUpdate = now;
                
                // Нормализуем значение ползунка от 0.0 до 1.0
                double v = speedSlider.getValue() / 100.0;
                
                // Экспоненциальный рост: от 2 до 3000 ходов в секунду.
                // На 50% скорости это будет плавно (около 75 ходов/сек).
                double ticksPerSecond = 2.0 * Math.pow(1500.0, v);
                long nsPerTick = (long) (1_000_000_000.0 / ticksPerSecond);
                
                accumulatedTime += elapsedNs;
                
                int ticksRan = 0;
                // Разрешаем выполнять несколько логических шагов за один кадр отрисовки
                // Ограничиваем 100 шагами за кадр, чтобы игра не зависла
                while (accumulatedTime >= nsPerTick && ticksRan < 100) {
                    if (visualGame != null && !visualGame.isGameOver()) {
                        visualGame.logicTick();
                        ticksRan++;
                    }
                    accumulatedTime -= nsPerTick;
                }
                
                // Сбрасываем излишки времени при сильных лагах
                if (ticksRan == 100) {
                    accumulatedTime = 0;
                }
                
                // Перерисовываем графику, только если состояние игры изменилось
                if (ticksRan > 0) {
                    drawGame();
                    updateScoreUI();
                    if (visualGame != null && visualGame.isGameOver()) {
                        pauseGame();
                        int winner = visualGame.getWinner();
                        statusLabel.setText(winner == 0 ? "Ничья!" : "Победил Бот " + winner);
                    }
                }
            }
            
            // Гарантируем чистый старт после паузы
            @Override
            public void start() {
                lastUpdate = 0;
                accumulatedTime = 0;
                super.start();
            }
        };
    }
    
    private boolean compileBots() {
        currentBot1Class = tryCompile("1", bot1CodeArea.getText());
        if (currentBot1Class == null) return false;
        currentBot2Class = tryCompile("2", bot2CodeArea.getText());
        if (currentBot2Class == null) return false;
        return true;
    }
    
    private Class<? extends Bot> tryCompile(String botName, String text) {
        String className = BotCompiler.className();
        String sourceCode = BotCompiler.sourceCode(className, text);
        try {
            return BotCompiler.compileBot(className, sourceCode);
        } catch (Exception exception) {
            StringBuilder fullCode = new StringBuilder();
            int i = 1;
            for (var line : sourceCode.lines().toList()) {
                fullCode.append(i);
                fullCode.append(": ");
                fullCode.append(line);
                fullCode.append("\n");
                i++;
            }
            showErrorDialog("Ошибка компиляции бота "+botName, exception.getClass().getCanonicalName()+"\n"+exception.getMessage()+"\n"+fullCode);
        }
        return null;
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
        gc.setLineWidth(1.0);
        for (int i = 0; i <= GameEngine.COLS; i++) {
            gc.strokeLine(i * cellSize, 0, i * cellSize, canvas.getHeight());
            gc.strokeLine(0, i * cellSize, canvas.getWidth(), i * cellSize);
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
                    gc.fillRect(x * cellSize, y * cellSize, cellSize, cellSize);
                } else if (grid[x][y] == GameEngine.PLAYER_2) {
                    gc.setFill(COLOR_TERR2);
                    gc.fillRect(x * cellSize, y * cellSize, cellSize, cellSize);
                }
            }
        }
        
        gc.setFill(COLOR_BOT1);
        gc.fillRect(visualGame.getBot1X() * cellSize, visualGame.getBot1Y() * cellSize, cellSize, cellSize);
        
        gc.setFill(COLOR_BOT2);
        gc.fillRect(visualGame.getBot2X() * cellSize, visualGame.getBot2Y() * cellSize, cellSize, cellSize);
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
        simStage.setTitle("Симуляция " + SIM_COUNT + " игр");
        
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        vbox.setAlignment(Pos.CENTER);
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(250);
        vbox.getChildren().addAll(new Label("Идет симуляция в фоне..."), progressBar);
        
        simStage.setScene(new Scene(vbox, 300, 100));
        simStage.show();
        
        AtomicInteger progress = new AtomicInteger(0);
        
        Thread simThread = new Thread(() -> {
            long[] results = IntStream.range(0, SIM_COUNT)
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
                        Platform.runLater(() -> progressBar.setProgress((double) finishedCount / SIM_COUNT));
                        
                        return engine.getWinner();
                    } catch (Exception e) {
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
        alert.setTitle("Результаты " + SIM_COUNT + " симуляций");
        alert.setHeaderText("Битва алгоритмов завершена");
        alert.setContentText(String.format("Бот 1 (Красный) победил: %d раз\nБот 2 (Синий) победил: %d раз\nНичья: %d раз", w1, w2, draws));
        alert.show();
    }
    
    private String getBot1DefaultCode() {
        return """
            var dirs = Direction.values();
            if (!mem.containsKey("dirIndex")) mem.put("dirIndex", 1);
            int dirIndex = (int) mem.get("dirIndex");
            
            Direction currDir = dirs[dirIndex];
            
            if (api.next(currDir) != 0) {
                dirIndex = (dirIndex + 1) % 4;
                mem.put("dirIndex", dirIndex);
                currDir = dirs[dirIndex];
            }
            
            if (api.next(currDir) != 0) {
                for (Direction d : dirs) {
                    if (api.next(d) == api.id()) return d;
                }
            }
            return currDir;
            """;
    }
    
    private String getBot2DefaultCode() {
        return """
            var dirs = new ArrayList<>(Arrays.asList(Direction.values()));
            Collections.shuffle(dirs);
            
            for (Direction dir : dirs) {
                if (api.next(dir) == 0) return dir;
            }
            
            for (Direction dir : dirs) {
                if (api.next(dir) == api.id()) return dir;
            }
            return Direction.UP;
            """;
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}