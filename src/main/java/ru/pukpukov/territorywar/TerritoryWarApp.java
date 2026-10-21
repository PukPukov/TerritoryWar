package ru.pukpukov.territorywar;

import module java.base;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.*;
import org.checkerframework.checker.nullness.qual.Nullable;
import ru.pukpukov.territorywar.api.Bot;

import static ru.pukpukov.territorywar.api.Util.fnnul;

public final class TerritoryWarApp extends Application {
    private static final Color[] BOT_COLORS = {Color.web("#FF5A5A"), Color.web("#5A82FF")};
    private static final Color[] TERRITORY_COLORS = {Color.web("#FFBDBD"), Color.web("#BDCFFF")};
    private final Canvas canvas = new Canvas();
    private final Label[] scores = {new Label(), new Label()};
    private final Label status = new Label("Ожидание...");
    private final TextArea[] code = {new TextArea(), new TextArea()};
    private final Slider speed = new Slider(0, 100, 50);
    private final TextField count = new TextField("10000");
    private final ComboBox<String> winner = new ComboBox<>();
    private final Button watch = new Button("Смотреть запись");
    private final CodeSyncManager sync = new CodeSyncManager(TerritoryWarApp::showError);
    private @Nullable GameEngine game;
    private @Nullable GameReplay replay;
    private boolean playingReplay;
    private double cellSize;

    private final AnimationTimer loop = new AnimationTimer() {
        private long lastUpdate, accumulated;

        @Override public void start() {
            lastUpdate = accumulated = 0;
            super.start();
        }

        @Override public void handle(long now) {
            GameEngine current = game;
            if (current == null || current.isGameOver()) return;
            if (lastUpdate == 0) lastUpdate = now;
            accumulated += now - lastUpdate;
            lastUpdate = now;
            long nsPerTick = (long) (1_000_000_000 / (2 * Math.pow(1500, speed.getValue() / 100)));
            int ticks = 0;
            while (accumulated >= nsPerTick && ticks < 100 && !current.isGameOver()) {
                current.logicTick();
                accumulated -= nsPerTick;
                ticks++;
            }
            if (ticks == 100) accumulated = 0;
            if (ticks > 0) refresh();
            if (current.isGameOver()) {
                stop();
                status.setText((playingReplay ? "Запись: " : "") + (current.winner() == 0 ? "Ничья!" : "Победил Бот " + current.winner()));
            }
        }
    };

    @Override public void start(Stage stage) throws IOException {
        stage.setTitle("Territory War");
        SplitPane split = new SplitPane(gamePanel(stage), editors());
        split.setDividerPositions(0.55);
        stage.setScene(new Scene(split, 1100, 700));
        stage.setMinWidth(800);
        stage.setMinHeight(500);
        sync.bind(Paths.get("bot1.java"), code[0], getBot1DefaultCode());
        sync.bind(Paths.get("bot2.java"), code[1], getBot2DefaultCode());
        sync.start();
        stage.show();
        refresh();
    }

    @Override public void stop() throws IOException {
        loop.stop();
        sync.close();
    }

    private Pane gamePanel(Stage stage) {
        HBox scoreboard = new HBox(25, scores[0], status, scores[1]);
        scoreboard.setAlignment(Pos.CENTER);
        for (int i = 0; i < scores.length; i++) scores[i].setTextFill(BOT_COLORS[i]);
        StackPane field = new StackPane(canvas);
        field.setStyle("-fx-border-color: #ccc; -fx-background-color: white;");
        field.widthProperty().addListener((obs, old, value) -> resize(field));
        field.heightProperty().addListener((obs, old, value) -> resize(field));
        VBox.setVgrow(field, Priority.ALWAYS);
        speed.setPrefWidth(120);
        count.setPrefColumnCount(7);
        count.setId("simulation-count");
        winner.getItems().setAll("Бот 1", "Бот 2");
        winner.getSelectionModel().selectFirst();
        watch.setDisable(true);
        watch.setOnAction(e -> {
            pause();
            playingReplay = true;
            game = fnnul(replay).newGame();
            refresh();
            startGame();
        });
        VBox panel = new VBox(10, scoreboard, field,
            row(button("▶ Старт", this::startGame), button("Пауза", this::pause), button("Сброс", () -> {
                pause();
                game = null;
                playingReplay = false;
                status.setText("Ожидание...");
                refresh();
            }), new Label("Скорость:"), speed),
            row(new Label("Число игр:"), count, button("Симулировать", () -> simulate(stage, 0))),
            row(new Label("Победа:"), winner, button("Искать победу", () -> simulate(stage, winner.getSelectionModel().getSelectedIndex() + 1)), watch));
        panel.setPadding(new Insets(10));
        panel.setStyle("-fx-background-color: #FAFAFA;");
        return panel;
    }

    private Pane editors() {
        VBox[] boxes = new VBox[code.length];
        for (int i = 0; i < code.length; i++) {
            code[i].setFont(Font.font("Monospaced", 14));
            VBox.setVgrow(code[i], Priority.ALWAYS);
            Label label = new Label("Код Бота " + (i + 1));
            label.setTextFill(BOT_COLORS[i]);
            boxes[i] = new VBox(5, label, code[i]);
        }
        SplitPane split = new SplitPane(boxes);
        split.setOrientation(Orientation.VERTICAL);
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox panel = new VBox(10, createRulesPane(), split);
        panel.setPadding(new Insets(10));
        return panel;
    }

    private static FlowPane row(Node... nodes) {
        FlowPane row = new FlowPane(8, 8, nodes);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    private static Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.setOnAction(e -> action.run());
        return button;
    }

    private @Nullable Simulations compileBots() {
        List<Class<? extends Bot>> compiled = new ArrayList<>();
        for (int i = 0; i < code.length; i++) {
            try {
                compiled.add(BotCompiler.compile(code[i].getText()));
            } catch (RuntimeException e) {
                showError(new IllegalArgumentException("Ошибка компиляции Бота " + (i + 1), e));
                return null;
            }
        }
        return new Simulations(compiled.get(0), compiled.get(1));
    }

    private void startGame() {
        if (game == null || game.isGameOver()) {
            if (playingReplay) {
                game = fnnul(replay).newGame();
            } else {
                Simulations bots = compileBots();
                if (bots == null) return;
                try { game = bots.newGame(false); }
                catch (RuntimeException e) { showError(e); return; }
            }
            refresh();
        }
        status.setText(playingReplay ? "Просмотр записи..." : "Игра идёт...");
        loop.start();
    }

    private void pause() {
        loop.stop();
        if (game != null && !game.isGameOver()) status.setText(playingReplay ? "Запись: пауза" : "Пауза");
    }

    private void resize(Pane field) {
        cellSize = Math.max(0, Math.floor(Math.min((field.getWidth() - 10) / GameEngine.COLS, (field.getHeight() - 10) / GameEngine.ROWS)));
        canvas.setWidth(cellSize * GameEngine.COLS);
        canvas.setHeight(cellSize * GameEngine.ROWS);
        refresh();
    }

    private void refresh() {
        var gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        gc.setStroke(Color.web("#e0e0e0"));
        gc.setLineWidth(1);
        for (int x = 0; x <= GameEngine.COLS; x++) gc.strokeLine(x * cellSize, 0, x * cellSize, canvas.getHeight());
        for (int y = 0; y <= GameEngine.ROWS; y++) gc.strokeLine(0, y * cellSize, canvas.getWidth(), y * cellSize);
        GameEngine current = game;
        if (current != null) {
            int[][] grid = current.grid();
            for (int x = 0; x < GameEngine.COLS; x++) for (int y = 0; y < GameEngine.ROWS; y++) {
                int owner = grid[x][y];
                if (owner == GameEngine.EMPTY) continue;
                gc.setFill(TERRITORY_COLORS[owner - 1]);
                gc.fillRect(x * cellSize, y * cellSize, cellSize, cellSize);
            }
        }
        for (int i = 0; i < scores.length; i++) {
            scores[i].setText("Бот " + (i + 1) + ": " + (current == null ? 1 : current.score(i + 1)));
            if (current == null) continue;
            var bot = current.bot(i + 1);
            gc.setFill(BOT_COLORS[i]);
            gc.fillRect(bot.x() * cellSize, bot.y() * cellSize, cellSize, cellSize);
        }
    }

    private void simulate(Stage owner, int target) {
        int games = 0;
        if (target == 0) {
            try {
                games = Integer.parseInt(count.getText().strip());
                if (games <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                showError(new IllegalArgumentException("Число игр должно быть целым от 1 до " + Integer.MAX_VALUE));
                return;
            }
        }
        pause();
        Simulations bots = compileBots();
        if (bots == null) return;
        final int limit = games;
        Task<Simulations.Result> task = new Task<>() {
            @Override protected Simulations.Result call() {
                return bots.run(limit, target, this::isCancelled, done -> {
                    updateMessage("Завершено игр: " + done);
                    updateProgress(target == 0 ? done : -1, limit);
                });
            }
        };
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(target == 0 ? "Симуляция " + limit + " игр" : "Поиск победы Бота " + target);
        Label message = new Label("Запуск...");
        message.textProperty().bind(task.messageProperty());
        ProgressBar progress = new ProgressBar(target == 0 ? 0 : -1);
        progress.progressProperty().bind(task.progressProperty());
        VBox panel = new VBox(15, message, progress, button("Отмена", () -> task.cancel(true)));
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(20));
        dialog.setScene(new Scene(panel, 330, 150));
        dialog.setOnCloseRequest(e -> task.cancel(true));
        task.setOnCancelled(e -> dialog.close());
        task.setOnFailed(e -> { dialog.close(); showError(task.getException()); });
        task.setOnSucceeded(e -> {
            dialog.close();
            Simulations.Result result = task.getValue();
            if (result.replay() != null) {
                replay = result.replay();
                watch.setDisable(false);
            }
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(owner);
            alert.setTitle("Результаты " + result.count() + " игр");
            alert.setHeaderText(target == 0 ? "Симуляция завершена" : "Победа найдена — запись готова к просмотру");
            alert.setContentText("Бот 1: " + result.wins1() + " побед\nБот 2: " + result.wins2() + " побед\nНичьих: " + result.draws());
            alert.show();
        });
        dialog.show();
        Thread.ofPlatform().daemon().name("simulations").start(task);
    }

    private static void showError(Throwable error) {
        var text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        TextArea details = new TextArea(text.toString());
        details.setEditable(false);
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText(error.toString());
        alert.getDialogPane().setExpandableContent(details);
        alert.getDialogPane().setExpanded(true);
        alert.show();
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
            • api.tickCount() - возвращает номер тика (0, 1, 2, 3, 4...)
            • api.enemy() -> BotAPI - доступ к данным врага
            • api.id() - возвращает ваш ID (1 или 2)
            • api.x() / api.y() - ваши текущие координаты
            • api.get(x, y) - значение клетки: 0 (пусто), 1 (Красный), 2 (Синий), -1 (край карты)
            • api.nextX(Direction dir) - возвращает x-координату клетки, находящейся по направлению dir
            • api.nextY(Direction dir) - возвращает y-координату клетки, находящейся по направлению dir
            • api.next(Direction dir) - утилитарный метод, возвращает значение клетки, находящейся по направлению dir
            • api.canMoveTo(Direction dir) - утилитарный метод для проверки, можно ли сходить в данную клетку
            
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
                if (api.canMoveTo(dir)) return dir;
            }
            
            return Direction.UP;
            """;
    }
    
    public static void main(String[] args) { launch(args); }
}
