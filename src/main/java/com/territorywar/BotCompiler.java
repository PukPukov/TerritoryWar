package com.territorywar;

import com.territorywar.api.Bot;

import javax.tools.*;
import java.io.File;
import java.io.FileWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public class BotCompiler {
    private static final AtomicInteger classCounter = new AtomicInteger(0);
    
    @SuppressWarnings("unchecked")
    public static Class<? extends Bot> compileBot(String userCode) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("JavaCompiler не найден. Убедитесь, что используете JDK, а не JRE.");
        }
        
        String className = "RuntimeBot" + classCounter.incrementAndGet();
        
        String sourceCode = "package com.territorywar;\n" +
            "import com.territorywar.api.*;\n" +
            "import java.util.*;\n" +
            "public class " + className + " implements Bot {\n" +
            "    @Override public Direction move(BotAPI api) {\n" +
            userCode + "\n" +
            "    }\n" +
            "}\n";
        
        // Создаем временную директорию
        File tempDir = Files.createTempDirectory("bots").toFile();
        tempDir.deleteOnExit();
        
        File sourceFile = new File(tempDir, className + ".java");
        sourceFile.deleteOnExit(); // Удаляем исходник при закрытии
        
        try (FileWriter writer = new FileWriter(sourceFile)) {
            writer.write(sourceCode);
        }
        
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);
        
        Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(Arrays.asList(sourceFile));
        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, null, null, compilationUnits);
        
        boolean success = task.call();
        fileManager.close();
        
        if (!success) {
            StringBuilder errorMsg = new StringBuilder("Ошибка компиляции:\n");
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                errorMsg.append("Строка ").append(diagnostic.getLineNumber() - 5) // Компенсируем добавленные импорты
                    .append(": ").append(diagnostic.getMessage(null)).append("\n");
            }
            throw new RuntimeException(errorMsg.toString());
        }
        
        // Убеждаемся, что сгенерированный .class файл тоже будет удален
        File classFile = new File(tempDir, className + ".class");
        classFile.deleteOnExit();
        
        // Загружаем скомпилированный класс
        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{tempDir.toURI().toURL()})) {
            return (Class<? extends Bot>) Class.forName("com.territorywar." + className, true, classLoader);
        }
    }
}