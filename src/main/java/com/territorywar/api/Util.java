package com.territorywar.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Util {
    
    
    
    public static <T> List<T> toList(T[] arr) {
        return new ArrayList<>(Arrays.asList(arr));
    };
    
}
