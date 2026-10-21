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
    private final GameLibrary library = new InMemoryGameLibrary();
    private final ComboBox<GameLibrary.Entry> recordings = new ComboBox<>();
    private @Nullable BoardState board;
    private @Nullable PointInput input;
    private @Nullable Task<BoardState> step;
    private volatile boolean running;
    private boolean playingReplay;
    private double cellSize;

    private final AnimationTimer loop = new AnimationTimer() {
        private long lastUpdate, accumulated;

        @Override public void start() {
            lastUpdate = accumulated = 0;
            super.start();
        }

        @Override public void handle(long now) {
            if (step != null) {
                lastUpdate = now;
                accumulated = 0; // A blocking bot must not accumulate catch-up time.
                return;
            }
            GameEngine current = game;
            if (current == null || current.isGameOver()) return;
            if (lastUpdate == 0) lastUpdate = now;
            accumulated += now - lastUpdate;
            lastUpdate = now;
            long nsPerTick = (long) (1_000_000_000 / (2 * Math.pow(1500, speed.getValue() / 100)));
            int ticks = (int) Math.min(100, accumulated / nsPerTick);
            if (ticks == 0) return;
            accumulated = ticks == 100 ? 0 : accumulated - ticks * nsPerTick;
            Task<BoardState> task = new Task<>() {
                @Override protected BoardState call() {
                    for (int i = 0; i < ticks && running && !isCancelled() && !current.isGameOver(); i++) current.logicTick();
                    return BoardState.capture(current.bot(1));
                }
            };
            step = task;
            task.setOnSucceeded(event -> {
                if (step != task) return; // Reset/replay may have replaced this session.
                step = null;
                display(task.getValue());
                if (current.isGameOver()) {
                    pause();
                    if (!playingReplay) addRecording("Игра " + (library.entries().size() + 1), current.recording());
                    status.setText((playingReplay ? "Запись: " : "") + (current.winner() == 0 ? "Ничья!" : "Победил Бот " + current.winner()));
                }
            });
            task.setOnFailed(event -> {
                if (step != task) return;
                discardGame();
                status.setText("Ошибка выполнения игры");
                showError(task.getException());
            });
            Thread.ofVirtual().name("game-step").start(task);
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
        discardGame();
        sync.close();
    }

    private Pane gamePanel(Stage stage) {
        HBox scoreboard = new HBox(25, scores[0], status, scores[1]);
        scoreboard.setAlignment(Pos.CENTER);
        for (int i = 0; i < scores.length; i++) scores[i].setTextFill(BOT_COLORS[i]);
        canvas.setId("game-field");
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
        recordings.setId("recordings");
        recordings.setPrefWidth(260);
        recordings.setPromptText("Записи текущего сеанса");
        watch.disableProperty().bind(recordings.valueProperty().isNull());
        watch.setOnAction(e -> {
            discardGame();
            playingReplay = true;
            startGame();
        });
        VBox panel = new VBox(10, scoreboard, field,
            row(button("▶ Старт", this::startGame), button("Пауза", this::pause), button("Сброс", () -> {
                discardGame();
                playingReplay = false;
                status.setText("Ожидание...");
                refresh();
            }), new Label("Скорость:"), speed),
            row(new Label("Число игр:"), count, button("Симулировать", () -> simulate(stage, 0))),
            row(new Label("Победа:"), winner, button("Искать победу", () -> simulate(stage, winner.getSelectionModel().getSelectedIndex() + 1))),
            row(new Label("Записи:"), recordings, watch));
        panel.setPadding(new Insets(10));
        panel.setStyle("-fx-background-color: #FAFAFA;");
        return panel;
    }

    private Pane editors() {
        VBox[] boxes = new VBox[code.length];
        for (int i = 0; i < code.length; i++) {
            code[i].setId("bot-code-" + (i + 1));
            code[i].setFont(Font.font("Monospaced", 14));
            VBox.setVgrow(code[i], Priority.ALWAYS);
            Label label = new Label("Код Бота " + (i + 1));
            label.setTextFill(BOT_COLORS[i]);
            int index = i;
            boxes[i] = new VBox(5, row(label, button("Загрузить…", () -> loadBot(index))), code[i]);
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

    private void loadBot(int index) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(canvas.getScene().getWindow());
        dialog.setTitle("Загрузить код Бота " + (index + 1));
        dialog.setHeaderText("Выберите пример или введите путь к Java-файлу");
        ComboBox<String> file = new ComboBox<>();
        file.setId("bot-file-path");
        file.setEditable(true);
        file.setMaxWidth(Double.MAX_VALUE);
        Path directory = Path.of("bots");
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                file.getItems().setAll(files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java"))
                    .sorted().map(Path::toString).toList());
            } catch (IOException e) { showError(e); return; }
        }
        file.setValue(file.getItems().isEmpty() ? "bots/manual.java" : file.getItems().getFirst());
        dialog.getDialogPane().setContent(file);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        // Use standard JavaFX controls, not GTK FileChooser: the latter aborts on hosts without GSettings schemas.
        dialog.setOnHidden(event -> {
            if (dialog.getResult() != ButtonType.OK) return;
            try { code[index].setText(Files.readString(Path.of(file.getEditor().getText().strip()))); }
            catch (IOException | InvalidPathException e) { showError(e); }
        });
        dialog.setResultConverter(button -> button);
        dialog.show();
    }

    private void startGame() {
        if (step == null && (game == null || game.isGameOver())) {
            if (playingReplay) {
                game = fnnul(recordings.getValue()).replay().newGame();
            } else {
                Simulations bots = compileBots();
                if (bots == null) return;
                if (input != null) input.close();
                input = new PointInput(canvas, this::display);
                try { game = bots.newGame(true, input); }
                catch (RuntimeException e) { discardGame(); showError(e); return; }
            }
            display(BoardState.capture(game.bot(1)));
        }
        running = true;
        status.setText(playingReplay ? "Просмотр записи..." : "Игра идёт...");
        loop.start();
    }

    private void pause() {
        running = false;
        loop.stop();
        if (game != null && (step != null || !game.isGameOver())) status.setText(playingReplay ? "Запись: пауза" : "Пауза");
    }

    private void discardGame() {
        pause();
        if (step != null) step.cancel(true);
        step = null;
        if (input != null) input.close();
        input = null;
        game = null;
        board = null;
    }

    private void display(BoardState snapshot) {
        board = snapshot;
        refresh();
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
        BoardState current = board;
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
            scores[i].setText("Бот " + (i + 1) + ": " + (current == null ? 1 : current.players().get(i).score()));
            if (current == null) continue;
            var bot = current.players().get(i);
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
        AtomicBoolean cancelled = new AtomicBoolean();
        Task<Simulations.Result> task = new Task<>() {
            @Override protected Simulations.Result call() {
                return bots.run(limit, target, cancelled::get, done -> {
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
        Button cancel = new Button("Отмена");
        cancel.setOnAction(_ -> {
            cancelled.set(true);
            cancel.setDisable(true);
            cancel.setText("Останавливаем...");
        });
        VBox panel = new VBox(15, message, progress, cancel);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(20));
        dialog.setScene(new Scene(panel, 330, 150));
        dialog.setOnCloseRequest(e -> {
            e.consume();
            cancel.fire();
        });
        task.setOnFailed(e -> { dialog.close(); showError(task.getException()); });
        task.setOnSucceeded(e -> {
            dialog.close();
            Simulations.Result result = task.getValue();
            List<GameLibrary.Entry> saved = new ArrayList<>();
            for (GameReplay recording : new LinkedHashSet<>(result.best().values())) {
                String evaluators = result.best().entrySet().stream().filter(entry -> entry.getValue() == recording)
                    .map(entry -> entry.getKey().toString()).collect(Collectors.joining(", "));
                saved.add(addRecording("Поиск Бота " + target + " — " + evaluators, recording));
            }
            if (!saved.isEmpty()) recordings.getSelectionModel().select(saved.getFirst());
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(owner);
            alert.setTitle("Результаты " + result.count() + " игр");
            alert.setHeaderText(result.cancelled() ? "Поиск/симуляция остановлены" : target == 0 ? "Симуляция завершена" : "Победа найдена — запись готова к просмотру");
            String best = saved.stream().map(GameLibrary.Entry::toString).collect(Collectors.joining("\n"));
            if (target != 0 && saved.isEmpty()) best = "Нет завершённых игр — запись не создана.";
            alert.setContentText("Бот 1: " + result.wins1() + " побед\nБот 2: " + result.wins2() + " побед\nНичьих: " + result.draws() + "\n\n" + best);
            alert.show();
        });
        dialog.show();
        Thread.ofPlatform().daemon().name("simulations").start(task);
    }

    private GameLibrary.Entry addRecording(String title, GameReplay recording) {
        GameLibrary.Entry entry = new GameLibrary.Entry(title, recording);
        library.add(entry);
        recordings.getItems().setAll(library.entries());
        recordings.getSelectionModel().select(entry);
        return entry;
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
            Ваш ход должен вернуть одно из направлений: Direction.UP, DOWN, LEFT, RIGHT или NONE (стоять). Если бот врезается в чужую территорию или выдает Exception, он пропускает ход!
            
            Код в поле ввода является реализацией метода public Direction move(BotAPI api, Map<String, Object> mem).
            
            Доступные методы BotAPI:
            • api = api.normalized() - в начале каждого хода: всегда красный, включая координаты, поле и соперника. Для синего поворот на 180°, возвращаемый Direction преобразуется автоматически. Не смешивать системы координат в одном ходе.
            • api.askPoint() - блокирующий выбор точки кликом/попапом; null при отмене. Только обычная игра.
            • api.towards(x, y) - сначала X, затем Y; NONE при достижении. Стены не обходит.
            • api.towards(start, target) - ступеньки по прямой с исходным соотношением X:Y (точки BotInput.Point).
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
            var dirs = Direction.MOVING;
            if (!mem.containsKey("dirIndex")) mem.put("dirIndex", 1);
            int dirIndex = (int) mem.get("dirIndex");
            
            Direction currDir = dirs.get(dirIndex);
            
            if (api.next(currDir) != 0) {
                dirIndex = (dirIndex + 1) % dirs.size();
                mem.put("dirIndex", dirIndex);
                currDir = dirs.get(dirIndex);
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
            var dirs = new ArrayList<>(Direction.MOVING);
            Collections.shuffle(dirs, ThreadLocalRandom.current());
            
            for (Direction dir : dirs) {
                if (api.canMoveTo(dir)) return dir;
            }
            
            return Direction.UP;
            """;
    }
    
    public static void main(String[] args) { launch(args); }
}
