package ru.pukpukov.territorywar;

import module java.base;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import org.checkerframework.checker.nullness.qual.Nullable;
import ru.pukpukov.territorywar.api.*;

/** One live game's blocking input bridge. Dialog and canvas are FX-thread confined. */
final class PointInput implements BotInput, AutoCloseable {
    private final Canvas canvas;
    private final Consumer<BoardState> display;
    private @Nullable Dialog<@Nullable Point> dialog;
    private volatile boolean closed;

    PointInput(Canvas canvas, Consumer<BoardState> display) {
        this.canvas = canvas;
        this.display = display;
        canvas.setOnMouseClicked(event -> {
            Dialog<@Nullable Point> current = dialog;
            if (current == null || canvas.getWidth() == 0 || canvas.getHeight() == 0) return;
            int x = (int) (event.getX() * GameEngine.COLS / canvas.getWidth());
            int y = (int) (event.getY() * GameEngine.ROWS / canvas.getHeight());
            if (x >= 0 && y >= 0 && x < GameEngine.COLS && y < GameEngine.ROWS) {
                current.setResult(new Point(x, y));
                current.close();
            }
        });
    }

    @Override public @Nullable Point askPoint(BotAPI bot) {
        assert !Platform.isFxApplicationThread() : "Bot input must not block the UI";
        BoardState snapshot = BoardState.capture(bot);
        CompletableFuture<@Nullable Point> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            if (closed) { answer.cancel(false); return; }
            try {
                assert dialog == null : "Two concurrent requests in one game";
                display.accept(snapshot);
                Dialog<@Nullable Point> request = new Dialog<>();
                dialog = request;
                request.initOwner(canvas.getScene().getWindow());
                request.initModality(Modality.NONE);
                request.setTitle("Точка для Бота " + bot.id());
                request.setHeaderText("Кликните по полю игры или задайте координаты");
                Spinner<Integer> x = new Spinner<>(0, GameEngine.COLS - 1, bot.x());
                Spinner<Integer> y = new Spinner<>(0, GameEngine.ROWS - 1, bot.y());
                x.setId("target-x");
                y.setId("target-y");
                request.getDialogPane().setContent(new VBox(8, new Label("X:"), x, new Label("Y:"), y));
                request.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                request.setResultConverter(button -> button == ButtonType.OK ? new Point(x.getValue(), y.getValue()) : null);
                request.setOnHidden(event -> { dialog = null; answer.complete(request.getResult()); });
                request.show();
            } catch (RuntimeException e) { answer.completeExceptionally(e); }
        });
        try {
            return answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Игра остановлена во время запроса точки");
        } catch (ExecutionException e) {
            throw new BotInput.Failure("Не удалось запросить точку", e.getCause());
        }
    }

    @Override public void close() {
        assert Platform.isFxApplicationThread();
        closed = true;
        if (dialog != null) dialog.close();
        canvas.setOnMouseClicked(event -> { });
    }
}