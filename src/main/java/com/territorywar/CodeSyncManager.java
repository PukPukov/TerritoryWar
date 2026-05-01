package com.territorywar;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CodeSyncManager {
    private static final Logger log = Logger.getLogger(CodeSyncManager.class.getName());
    
    private final TextArea area1;
    private final TextArea area2;
    private final Path path1 = Paths.get("bot1.java");
    private final Path path2 = Paths.get("bot2.java");
    
    public CodeSyncManager(TextArea area1, TextArea area2) {
        this.area1 = area1;
        this.area2 = area2;
    }
    
    public void init(String defaultBot1, String defaultBot2) {
        // Инициализируем файлы и поля ввода
        setupFileAndUI(path1, area1, defaultBot1);
        setupFileAndUI(path2, area2, defaultBot2);
        
        // Вешаем слушатели на ввод пользователя с задержкой 500мс (Debounce)
        setupUiListener(area1, path1);
        setupUiListener(area2, path2);
        
        // Запускаем фоновый поток слежения за диском
        startFileWatcher();
    }
    
    private void setupFileAndUI(Path path, TextArea area, String defaultCode) {
        if (Files.exists(path)) {
            try {
                area.setText(Files.readString(path));
            } catch (IOException e) {
                log.warning("Не удалось прочитать " + path.getFileName());
                area.setText(defaultCode);
            }
        } else {
            area.setText(defaultCode);
            writeToFile(path, defaultCode);
        }
    }
    
    private void setupUiListener(TextArea area, Path path) {
        PauseTransition debounce = new PauseTransition(Duration.millis(500));
        debounce.setOnFinished(e -> writeToFile(path, area.getText()));
        
        area.textProperty().addListener((obs, oldVal, newVal) -> {
            // Перезапускаем таймер при каждом нажатии, 
            // сохранение произойдет только если ввода не было 500мс
            debounce.playFromStart();
        });
    }
    
    private void writeToFile(Path path, String content) {
        try {
            // Если текст не изменился, ничего не пишем (предотвращает циклы обновления)
            if (Files.exists(path)) {
                String currentFileContent = Files.readString(path);
                if (normalize(currentFileContent).equals(normalize(content))) {
                    return;
                }
            }
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warning("Не удалось сохранить " + path.getFileName() + ": " + e.getMessage());
        }
    }
    
    private void startFileWatcher() {
        Thread watcherThread = new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                Path dir = Paths.get("").toAbsolutePath();
                dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watchService.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        if (changed.getFileName().toString().equals(path1.getFileName().toString())) {
                            updateUiFromFileSafe(path1, area1);
                        } else if (changed.getFileName().toString().equals(path2.getFileName().toString())) {
                            updateUiFromFileSafe(path2, area2);
                        }
                    }
                    key.reset();
                }
            } catch (Exception e) {
                log.log(Level.WARNING, "Ошибка слежения за файлами (File watcher)", e);
            }
        });
        watcherThread.setDaemon(true); // Закроется вместе с приложением
        watcherThread.start();
    }
    
    private void updateUiFromFileSafe(Path path, TextArea area) {
        // Микро-пауза, чтобы сторонний редактор успел отпустить lock файла
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        
        try {
            String content = Files.readString(path);
            Platform.runLater(() -> {
                // Изменяем UI только если текст реально изменился извне
                if (!normalize(area.getText()).equals(normalize(content))) {
                    int caret = area.getCaretPosition();
                    area.setText(content);
                    // Попытка восстановить позицию курсора
                    area.positionCaret(Math.min(caret, content.length()));
                }
            });
        } catch (IOException e) {
            log.warning("Ошибка чтения обновленного файла " + path.getFileName());
        }
    }
    
    private String normalize(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }
}