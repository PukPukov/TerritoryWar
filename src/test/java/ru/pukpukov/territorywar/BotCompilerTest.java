package ru.pukpukov.territorywar;

import module java.base;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BotCompilerTest {
    @Test void compilesModernJavaInMemory() throws Exception {
        var clazz = BotCompiler.compile("record Choice(Direction d) {} return new Choice(Direction.RIGHT).d();");
        assertNotNull(clazz.getDeclaredConstructor().newInstance());
    }

    @Test void reportsCompilationDiagnosticsAndIsolatesVersions() {
        var first = BotCompiler.compile("return Direction.RIGHT;");
        var second = BotCompiler.compile("return Direction.LEFT;");
        assertNotEquals(first, second);
        RuntimeException error = assertThrows(RuntimeException.class, () -> BotCompiler.compile("return noSuchName;"));
        assertTrue(error.getMessage().contains("noSuchName"));
    }
}
