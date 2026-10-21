package ru.pukpukov.territorywar;

import org.checkerframework.checker.nullness.qual.Nullable;
import ru.pukpukov.territorywar.api.*;
import static ru.pukpukov.territorywar.api.Constants.FIELD_SIZE;

/** Blue's live view rotated 180 degrees; both players share the same frame. */
final class NormalizedBotAPI implements BotAPI {
    private final BotAPI actual;
    private @Nullable NormalizedBotAPI enemy;

    NormalizedBotAPI(BotAPI actual) {
        assert actual.id() == GameEngine.PLAYER_2 : "Only blue needs a rotated frame";
        this.actual = actual;
    }

    private NormalizedBotAPI(BotAPI actual, NormalizedBotAPI enemy) {
        this.actual = actual;
        this.enemy = enemy;
    }

    @Override public BotAPI enemy() {
        // Link only after construction: the peer must never receive an under-initialized view.
        if (enemy == null) enemy = new NormalizedBotAPI(actual.enemy(), this);
        return enemy;
    }
    @Override public BotAPI normalized() { return actual.normalized(); }
    @Override public int tickCount() { return actual.tickCount(); }
    @Override public int id() { return color(actual.id()); }
    @Override public int x() { return coordinate(actual.x()); }
    @Override public int y() { return coordinate(actual.y()); }
    @Override public int get(int x, int y) { return color(actual.get(coordinate(x), coordinate(y))); }

    @Override public BotInput.@Nullable Point askPoint() {
        BotInput.Point point = actual.askPoint();
        return point == null ? null : new BotInput.Point(coordinate(point.x()), coordinate(point.y()));
    }

    private static int coordinate(int value) { return FIELD_SIZE - 1 - value; }
    private static int color(int value) { return value == GameEngine.PLAYER_1 ? GameEngine.PLAYER_2 : value == GameEngine.PLAYER_2 ? GameEngine.PLAYER_1 : value; }
}
