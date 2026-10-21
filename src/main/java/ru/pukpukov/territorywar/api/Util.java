package ru.pukpukov.territorywar.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public class Util {
    
    public static <T> List<T> toList(T[] arr) {
        return new ArrayList<>(Arrays.asList(arr));
    }

    @SuppressWarnings("cast.unsafe")
    public static <T> @NonNull T fnnul(@Nullable T value) {
        assert value != null;
        return (@NonNull T) value;
    }

}
