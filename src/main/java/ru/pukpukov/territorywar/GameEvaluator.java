package ru.pukpukov.territorywar;

import java.util.List;
import java.util.function.IntBinaryOperator;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum GameEvaluator {
    DIFFERENCE("Разница", (own, opponent) -> own - opponent),
    MAXIMUM("Максимум", (own, _) -> own);

    public static final List<GameEvaluator> ALL = List.of(values());
    private final String title;
    private final IntBinaryOperator evaluate;

    public int value(int own, int opponent) { return evaluate.applyAsInt(own, opponent); }
    @Override public String toString() { return title; }
}
