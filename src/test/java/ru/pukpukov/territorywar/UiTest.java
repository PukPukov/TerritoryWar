package ru.pukpukov.territorywar;

import module java.base;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.PickResult;
import javafx.scene.image.WritableImage;
import javafx.stage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Runs in an isolated working directory: autosave can never touch the user's bots. */
@EnabledIfSystemProperty(named = "ui.test", matches = "true")
class UiTest {
    @Test void completeUiFlow(@TempDir Path directory) throws Exception {
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .map(path -> Path.of(path).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        String packaged = System.getProperty("packaged.jar");
        if (packaged != null) classpath = Path.of(packaged).toAbsolutePath() + File.pathSeparator + classpath;
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-ea", "--enable-native-access=ALL-UNNAMED", "-Dprism.order=sw", "-cp", classpath, Scenario.class.getName())
            .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("ui.log").toFile()).start();
        boolean ended = child.waitFor(45, TimeUnit.SECONDS);
        if (!ended) child.destroyForcibly();
        assertTrue(ended, "UI test timed out: " + Files.readString(directory.resolve("ui.log")));
        assertEquals(0, child.exitValue(), Files.readString(directory.resolve("ui.log")));
        Path snapshot = Files.createTempFile("territory-war-ui-", ".png");
        Files.copy(directory.resolve("ui.png"), snapshot, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("UI screenshot: " + snapshot);
    }

    public static final class Scenario {
        private static Stage stage;
        private static TerritoryWarApp app;

        static <T> T fx(Callable<T> action) throws Exception {
            FutureTask<T> task = new FutureTask<>(action);
            Platform.runLater(task);
            return task.get(10, TimeUnit.SECONDS);
        }

        static void await(Callable<Boolean> condition) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!fx(condition)) {
                if (System.nanoTime() > end) throw new AssertionError("UI condition timed out");
                Thread.sleep(20);
            }
        }

        static Button button(Window window, String text) {
            return window.getScene().getRoot().lookupAll(".button").stream().filter(node -> node instanceof Button)
                .map(node -> (Button) node).filter(node -> node.getText().equals(text)).findFirst().orElseThrow();
        }

        static boolean window(String title) {
            return Window.getWindows().stream().anyMatch(w -> w instanceof Stage s && s.getTitle().equals(title));
        }

        static void closeDialogs() {
            for (Window window : List.copyOf(Window.getWindows())) if (window != stage) window.hide();
        }

        static boolean labelContains(String value) {
            return stage.getScene().getRoot().lookupAll(".label").stream()
                .anyMatch(node -> node instanceof Label label && label.getText().contains(value));
        }

        static ComboBox<?> recordings() { return (ComboBox<?>) stage.getScene().lookup("#recordings"); }

        static void startedGameIsArchived() throws Exception {
            int previous = fx(() -> recordings().getItems().size());
            fx(() -> {
                button(stage, "Сброс").fire();
                ((TextArea) stage.getScene().lookup("#bot-code-1")).setText("return Direction.RIGHT;");
                ((TextArea) stage.getScene().lookup("#bot-code-2")).setText("return Direction.NONE;");
                button(stage, "▶ Старт").fire();
                return null;
            });
            await(() -> labelContains("Победил Бот 1"));
            fx(() -> {
                assertEquals(previous + 1, recordings().getItems().size());
                assertTrue(recordings().getValue().toString().startsWith("Игра "));
                button(stage, "Смотреть запись").fire();
                return null;
            });
            await(() -> labelContains("Запись: Победил Бот 1"));
            fx(() -> { assertEquals(previous + 1, recordings().getItems().size(), "Playback must not archive itself"); return null; });
        }

        static GameEngine currentGame() throws Exception {
            var field = TerritoryWarApp.class.getDeclaredField("game");
            field.setAccessible(true);
            return (GameEngine) field.get(app);
        }

        static boolean stepFinished() throws Exception {
            var field = TerritoryWarApp.class.getDeclaredField("step");
            field.setAccessible(true);
            return field.get(app) == null;
        }

        static void clickCell(int x, int y) {
            Canvas canvas = (Canvas) stage.getScene().lookup("#game-field");
            double px = (x + .5) * canvas.getWidth() / GameEngine.COLS;
            double py = (y + .5) * canvas.getHeight() / GameEngine.ROWS;
            var scene = canvas.localToScene(px, py);
            canvas.fireEvent(new MouseEvent(
                MouseEvent.MOUSE_CLICKED, scene.getX(), scene.getY(), scene.getX(), scene.getY(), MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, false, false, true, new PickResult(canvas, scene.getX(), scene.getY())
            ));
        }

        static void manualFlow() throws Exception {
            // Fixed API test driver, not the user's manual.java algorithm.
            String manual = """
                var target = (BotInput.Point) mem.get("target");
                if (target == null || api.towards(target.x(), target.y()) == Direction.NONE) {
                    target = api.askPoint();
                    if (target == null) return Direction.NONE;
                    mem.put("target", target);
                }
                return api.towards(target.x(), target.y());
                """;
            Files.createDirectories(Path.of("bots"));
            Files.writeString(Path.of("bots/fixture.java"), manual);
            fx(() -> {
                button(stage, "Сброс").fire();
                // Exercise the actual Load button: a JavaFX popup, never a crashing native GTK chooser.
                Button load = stage.getScene().getRoot().lookupAll(".button").stream().filter(node -> node instanceof Button button && button.getText().equals("Загрузить…"))
                    .map(node -> (Button) node).findFirst().orElseThrow();
                load.fire();
                Window popup = Window.getWindows().stream().filter(w -> w instanceof Stage st && st.getTitle().equals("Загрузить код Бота 1")).findFirst().orElseThrow();
                ((ComboBox<?>) popup.getScene().lookup("#bot-file-path")).getEditor().setText(Path.of("bots/fixture.java").toAbsolutePath().toString());
                ((Button) ((DialogPane) popup.getScene().getRoot()).lookupButton(ButtonType.OK)).fire();
                assertEquals(manual, ((TextArea) stage.getScene().lookup("#bot-code-1")).getText());
                ((TextArea) stage.getScene().lookup("#bot-code-2")).setText("return Direction.LEFT;");
                button(stage, "▶ Старт").fire();
                return null;
            });
            await(() -> window("Точка для Бота 1"));
            int enemyTicks = fx(() -> currentGame().bot(2).tickCount());
            Thread.sleep(200);
            fx(() -> {
                assertEquals(enemyTicks, currentGame().bot(2).tickCount(), "A blocking question holds the current turn");
                stage.setWidth(900); // The FX thread remains responsive while move() waits.
                clickCell(34, 30);
                return null;
            });
            await(() -> window("Точка для Бота 1") && currentGame().bot(1).x() == 34);
            fx(() -> {
                assertEquals(30, currentGame().bot(1).y());
                button(stage, "Пауза").fire();
                clickCell(34, 32);
                return null;
            });
            await(Scenario::stepFinished);
            fx(() -> {
                assertTrue(labelContains("Пауза"));
                assertEquals(31, currentGame().bot(1).y());
                button(stage, "▶ Старт").fire();
                return null;
            });
            await(() -> window("Точка для Бота 1") && currentGame().bot(1).y() == 32);
            fx(() -> {
                button(stage, "Сброс").fire();
                assertFalse(window("Точка для Бота 1"), "Reset must close the outstanding prompt");
                assertNull(currentGame());
                ((TextArea) stage.getScene().lookup("#bot-code-1")).setText("return Direction.NONE;");
                ((TextArea) stage.getScene().lookup("#bot-code-2")).setText(manual);
                button(stage, "▶ Старт").fire();
                return null;
            });
            await(() -> window("Точка для Бота 2"));
            fx(() -> {
                // Coordinates in the standard popup work as well as map clicks.
                Window popup = Window.getWindows().stream().filter(w -> w instanceof Stage s && s.getTitle().equals("Точка для Бота 2")).findFirst().orElseThrow();
                ((Spinner<?>) popup.getScene().lookup("#target-x")).decrement(2);
                assertEquals(169, ((Spinner<?>) popup.getScene().lookup("#target-y")).getValue());
                ((Button) ((DialogPane) popup.getScene().getRoot()).lookupButton(ButtonType.OK)).fire();
                return null;
            });
            await(() -> window("Точка для Бота 2") && currentGame().bot(2).x() == 167);
            fx(() -> {
                button(stage, "Сброс").fire();
                ((TextField) stage.getScene().lookup("#simulation-count")).setText("1");
                button(stage, "Симулировать").fire();
                return null;
            });
            await(() -> stage.getScene().getRoot() != null && Window.getWindows().stream().anyMatch(w -> w != stage &&
                w.getScene().getRoot().lookupAll(".text-area").stream().anyMatch(node -> node instanceof TextArea area && area.getText().contains("Ручной ввод доступен"))));
            fx(() -> {
                assertFalse(window("Точка для Бота 2"), "Batch must not open a point prompt");
                closeDialogs();
                button(stage, "▶ Старт").fire();
                return null;
            });
            await(() -> window("Точка для Бота 2"));
        }

        public static void main(String[] args) throws Exception {
            try {
                Files.writeString(Path.of("bot1.java"), "return Direction.RIGHT;");
                Files.writeString(Path.of("bot2.java"), "return null;");
                Platform.startup(() -> {});
                Platform.setImplicitExit(false);
                fx(() -> {
                    Path conflict = Path.of("conflict.java");
                    Files.writeString(conflict, "original");
                    List<Throwable> errors = new ArrayList<>();
                    CodeSyncManager sync = new CodeSyncManager(errors::add);
                    TextArea editor = new TextArea();
                    sync.bind(conflict, editor, "fallback");
                    editor.setText("local edits");
                    Files.writeString(conflict, "external edits");
                    sync.close(); // Detect the conflict even before a queued watcher/debounce can run.
                    assertEquals("external edits", Files.readString(conflict));
                    assertEquals("external edits", editor.getText());
                    assertEquals(1, errors.size());
                    try (var paths = Files.list(Path.of("."))) {
                        Path backup = paths.filter(path -> path.getFileName().toString().startsWith("conflict.java.conflict-")).findFirst().orElseThrow();
                        assertEquals("local edits", Files.readString(backup));
                    }
                    assertThrows(IOException.class, () -> sync.bind(Files.createDirectory(Path.of("unreadable.java")), new TextArea(), "fallback"));
                    app = new TerritoryWarApp();
                    stage = new Stage();
                    app.start(stage);
                    return null;
                });
                fx(() -> {
                    TextField count = (TextField) stage.getScene().lookup("#simulation-count");
                    assertEquals("10000", count.getText());
                    count.setText("7");
                    button(stage, "Симулировать").fire();
                    return null;
                });
                await(() -> window("Результаты 7 игр"));
                fx(() -> {
                    assertTrue(recordings().getItems().isEmpty(), "Batch must not save recordings");
                    closeDialogs();
                    button(stage, "Искать победу").fire();
                    return null;
                });
                await(() -> window("Результаты 1 игр"));
                fx(() -> {
                    closeDialogs();
                    assertEquals(1, recordings().getItems().size(), "The same best match must be stored only once");
                    assertFalse(button(stage, "Смотреть запись").isDisabled());
                    for (var node : stage.getScene().getRoot().lookupAll(".text-area")) {
                        if (node instanceof TextArea area && area.getText().equals("return Direction.RIGHT;")) area.setText("invalid java source");
                    }
                    for (var node : stage.getScene().getRoot().lookupAll(".slider")) ((Slider) node).setValue(100);
                    button(stage, "Смотреть запись").fire();
                    return null;
                });
                await(() -> labelContains("Запись: Победил Бот 1"));
                fx(() -> { button(stage, "Смотреть запись").fire(); return null; });
                await(() -> labelContains("Запись: Победил Бот 1"));
                fx(() -> { assertEquals(1, recordings().getItems().size()); return null; });
                Files.writeString(Path.of("bot1.java"), "return null;");
                await(() -> stage.getScene().getRoot().lookupAll(".text-area").stream()
                    .filter(node -> node instanceof TextArea area && area.getText().equals("return null;")).count() == 2);
                fx(() -> {
                    // External edits may race the debounce: only the documented conflict dialog is allowed.
                    for (Window window : List.copyOf(Window.getWindows())) if (window != stage) {
                        assertTrue(((DialogPane) window.getScene().getRoot()).getHeaderText().contains("Конфликт правок"));
                    }
                    closeDialogs();
                    button(stage, "Искать победу").fire();
                    return null;
                });
                await(() -> Window.getWindows().stream().anyMatch(w -> w instanceof Stage s && s.getTitle().equals("Поиск победы Бота 1") &&
                    w.getScene().getRoot().lookupAll(".label").stream().anyMatch(node -> node instanceof Label label && label.getText().matches("Завершено игр: [1-9][0-9]*"))));
                fx(() -> {
                    Window dialog = Window.getWindows().stream().filter(w -> w instanceof Stage s && s.getTitle().equals("Поиск победы Бота 1")).findFirst().orElseThrow();
                    button(dialog, "Отмена").fire();
                    return null;
                });
                await(() -> !window("Поиск победы Бота 1"));
                fx(() -> {
                    assertEquals(2, recordings().getItems().size(), "Cancellation must save the best completed match without losing old games");
                    String selected = recordings().getValue().toString();
                    assertTrue(selected.contains("Разница, Максимум"));
                    assertTrue(selected.contains("[1:1]"));
                    assertTrue(Window.getWindows().stream().anyMatch(w -> w != stage && w.getScene().getRoot() instanceof DialogPane pane &&
                        pane.getHeaderText().contains("остановлен") && pane.getContentText().contains("Разница, Максимум")));
                    closeDialogs();
                    button(stage, "Смотреть запись").fire();
                    return null;
                });
                await(() -> labelContains("Запись: Ничья!"));
                fx(() -> {
                    assertEquals(2, recordings().getItems().size());
                    recordings().getSelectionModel().selectFirst();
                    button(stage, "Смотреть запись").fire();
                    return null;
                });
                await(() -> labelContains("Запись: Победил Бот 1"));
                fx(() -> {
                    ((TextField) stage.getScene().lookup("#simulation-count")).setText("0");
                    button(stage, "Симулировать").fire();
                    assertEquals(2, Window.getWindows().size(), "Invalid input should show an error dialog");
                    closeDialogs();
                    stage.setWidth(800);
                    stage.setHeight(500);
                    return null;
                });
                Thread.sleep(200);
                fx(() -> {
                    WritableImage image = stage.getScene().snapshot(null);
                    BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    boolean territoryVisible = false;
                    for (int x = 0; x < png.getWidth(); x++) for (int y = 0; y < png.getHeight(); y++) {
                        int pixel = image.getPixelReader().getArgb(x, y);
                        png.setRGB(x, y, pixel);
                        if (pixel == 0xffffbdbd) territoryVisible = true;
                    }
                    assertTrue(territoryVisible, "Grid strokes must not obscure territories at minimum window size");
                    ImageIO.write(png, "png", Path.of("ui.png").toFile());
                    return null;
                });
                startedGameIsArchived();
                manualFlow();
                fx(() -> {
                    app.stop();
                    stage.close();
                    return null;
                });
                Platform.exit();
                System.out.println("UI flow passed");
            } catch (Throwable e) {
                e.printStackTrace();
                System.exit(1);
            }
        }
    }
}
