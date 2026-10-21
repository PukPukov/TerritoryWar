package ru.pukpukov.territorywar;

import module java.base;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.util.Duration;
import org.checkerframework.checker.nullness.qual.Nullable;

import static ru.pukpukov.territorywar.api.Util.fnnul;

/** File state and editors are confined to the FX thread; the watcher only schedules reads. */
public final class CodeSyncManager implements AutoCloseable {
    private final Map<Path, TextArea> editors = new HashMap<>();
    private final Map<Path, String> saved = new HashMap<>();
    private final List<PauseTransition> timers = new ArrayList<>();
    private final Consumer<Throwable> errors;
    private @Nullable WatchService watcher;

    public CodeSyncManager(Consumer<Throwable> errors) { this.errors = errors; }

    public void bind(Path path, TextArea editor, String fallback) throws IOException {
        assert Platform.isFxApplicationThread();
        path = path.toAbsolutePath().normalize();
        try { editor.setText(Files.readString(path)); }
        catch (NoSuchFileException e) {
            Files.writeString(path, fallback, StandardOpenOption.CREATE_NEW);
            editor.setText(fallback);
        }
        editors.put(path, editor);
        saved.put(path, normalize(editor.getText()));
        Path file = path;
        PauseTransition timer = new PauseTransition(Duration.millis(500));
        timer.setOnFinished(e -> save(file, editor));
        editor.textProperty().addListener((obs, old, value) -> timer.playFromStart());
        timers.add(timer);
    }

    private void save(Path path, TextArea editor) {
        assert Platform.isFxApplicationThread();
        String text = editor.getText();
        if (normalize(text).equals(saved.get(path))) return;
        try {
            if (reload(path, editor)) return;
            Path temporary = Files.createTempFile(fnnul(path.getParent()), ".territory-bot-", ".java");
            try {
                Files.writeString(temporary, text);
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
            saved.put(path, normalize(text));
        } catch (IOException e) { errors.accept(e); }
    }

    public void start() throws IOException {
        assert Platform.isFxApplicationThread();
        WatchService service = FileSystems.getDefault().newWatchService();
        watcher = service;
        Set<Path> directories = new HashSet<>();
        for (Path path : editors.keySet()) directories.add(fnnul(path.getParent()));
        for (Path dir : directories) dir.register(service, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
        Thread.ofPlatform().daemon().name("bot-files").start(() -> {
            try {
                while (true) {
                    WatchKey key = service.take();
                    key.pollEvents();
                    Platform.runLater(this::reload);
                    if (!key.reset()) throw new IOException("Каталог ботов больше недоступен");
                }
            } catch (ClosedWatchServiceException e) {
                // Normal shutdown via close().
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) { Platform.runLater(() -> errors.accept(e)); }
        });
    }

    private void reload() {
        assert Platform.isFxApplicationThread();
        for (var entry : editors.entrySet()) {
            try { reload(entry.getKey(), entry.getValue()); }
            catch (IOException e) { errors.accept(e); }
        }
    }

    // Shared by watcher and save: an external version must never silently erase local edits.
    private boolean reload(Path path, TextArea editor) throws IOException {
        String text = Files.readString(path);
        String disk = normalize(text), local = normalize(editor.getText());
        String previous = fnnul(saved.get(path));
        if (disk.equals(previous)) return false;
        if (!local.equals(previous) && !local.equals(disk)) {
            Path backup = Files.createTempFile(fnnul(path.getParent()), path.getFileName() + ".conflict-", ".java");
            Files.writeString(backup, editor.getText());
            errors.accept(new IllegalStateException("Конфликт правок " + path + ": загружен файл с диска, локальный код сохранён в " + backup));
        }
        saved.put(path, disk);
        int caret = editor.getCaretPosition();
        editor.setText(text);
        editor.positionCaret(Math.min(caret, text.length()));
        return true;
    }

    private static String normalize(String text) { return text.replace("\r\n", "\n").replace('\r', '\n'); }

    @Override public void close() throws IOException {
        assert Platform.isFxApplicationThread();
        for (PauseTransition timer : timers) timer.stop();
        for (var entry : editors.entrySet()) save(entry.getKey(), entry.getValue());
        if (watcher != null) watcher.close();
    }
}
