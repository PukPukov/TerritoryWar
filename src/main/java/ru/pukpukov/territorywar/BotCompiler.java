package ru.pukpukov.territorywar;

import java.util.concurrent.atomic.AtomicInteger;
import org.joor.Reflect;
import ru.pukpukov.territorywar.api.Bot;

public final class BotCompiler {
    private static final AtomicInteger COUNTER = new AtomicInteger();

    public static Class<? extends Bot> compile(String code) {
        String name = "RuntimeBot" + COUNTER.incrementAndGet();
        String source = """
            package ru.pukpukov.territorywar;
            import ru.pukpukov.territorywar.api.*;
            import java.util.*;
            import java.util.concurrent.*;
            import static ru.pukpukov.territorywar.api.Util.*;
            import static ru.pukpukov.territorywar.api.Constants.*;
            public class %s implements Bot {
                public Direction move(BotAPI api, Map<String, Object> mem) {
            %s
                }
            }
            """.formatted(name, code);
        return Reflect.compile("ru.pukpukov.territorywar." + name, source).type().asSubclass(Bot.class);
    }
}
