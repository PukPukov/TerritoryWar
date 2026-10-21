package ru.pukpukov.territorywar;

import module java.base;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BotCompilerTest {
    @Test void compilesCurrentUserBotsAndModernJavaInMemory() throws Exception {
        for (String file : new String[]{"bot1.java", "bot2.java"}) {
            var bot = BotCompiler.compile(Files.readString(Path.of(file))).getDeclaredConstructor().newInstance();
            GameEngine game = GameEngine.builder().bot1(bot).bot2(GameEngineTest.bot(0)).build();
            game.logicTick();
            assertEquals(1, game.bot(1).tickCount());
        }
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
