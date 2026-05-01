package ru.pukpukov.territorywar;

import ru.pukpukov.territorywar.api.Bot;

import javax.tools.*;
import java.io.File;
import java.io.FileWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class BotCompiler {
    private static final Logger log = Logger.getLogger(BotCompiler.class.getName());
    private static final AtomicInteger classCounter = new AtomicInteger(0);
    
    public static String className() {
        return "RuntimeBot" + classCounter.incrementAndGet();
    }
    
    public static String sourceCode(String className, String userCode) {
        return """
            package ru.pukpukov.territorywar;
            import ru.pukpukov.territorywar.api.*;
            import java.lang.*;
            import java.util.*;
            
            import static ru.pukpukov.territorywar.api.Util.*;
            import static ru.pukpukov.territorywar.api.Constants.*;
            
            public class %s implements Bot {
                @Override
                public Direction move(BotAPI api, Map<String, Object> mem) {
            %s
                }
            }
            """.formatted(className, userCode);
    }
    
    @SuppressWarnings("unchecked")
    public static Class<? extends Bot> compileBot(String className, String sourceCode) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("JavaCompiler не найден. Убедитесь, что используете JDK, а не JRE.");
        }
        
        File tempDir = Files.createTempDirectory("bots").toFile();
        tempDir.deleteOnExit();
        
        File sourceFile = new File(tempDir, className + ".java");
        sourceFile.deleteOnExit();
        
        try (FileWriter writer = new FileWriter(sourceFile)) {
            writer.write(sourceCode);
        }
        
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            
            String classPath = System.getProperty("java.class.path");
            
            // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Добавлен аргумент "-d", чтобы компилятор 
            // автоматически создал иерархию папок для пакета com.territorywar
            List<String> options = Arrays.asList(
                "-classpath", classPath,
                "-d", tempDir.getAbsolutePath()
            );
            
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(Collections.singletonList(sourceFile));
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);
            
            boolean success = task.call();
            
            if (!success) {
                StringBuilder errorMsg = new StringBuilder("Ошибка компиляции:\n\n");
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    errorMsg.append(String.format("Строка %d: %s\n", diagnostic.getLineNumber(), diagnostic.getMessage(null)));
                }
                log.severe(errorMsg.toString());
                throw new RuntimeException(errorMsg.toString());
            }
        }
        
        // Помечаем файлы и папки на удаление при закрытии игры, чтобы не мусорить на диске
        File packageComDir = new File(tempDir, "com");
        File packageTerritoryWarDir = new File(packageComDir, "territorywar");
        File classFile = new File(packageTerritoryWarDir, className + ".class");
        
        classFile.deleteOnExit();
        packageTerritoryWarDir.deleteOnExit();
        packageComDir.deleteOnExit();
        
        // Загружаем скомпилированный класс из нужной директории
        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{tempDir.toURI().toURL()})) {
            return (Class<? extends Bot>) Class.forName("com.territorywar." + className, true, classLoader);
        }
    }
}