package com.sizylift.backend.imports.dto;

/** Совпадают с ограничениями payload v1; проверяются также перед обращением к publisher. */
public final class ImportLimits {
    private ImportLimits() { }
    public static final int NAME_CHARS = 200;
    public static final int NOTE_CHARS = 4000;
    public static final int RESOLUTIONS = 2000;
    public static final int CYCLES = 50;
    public static final int WEEKS = 104;
    public static final int DAYS_PER_WEEK = 7;
    public static final int WORKOUTS_PER_WEEK = 14;
    public static final int BLOCKS = 100;
    public static final int EXERCISES_PER_BLOCK = 50;
    public static final int SETS_PER_EXERCISE = 200;
    public static final int REPETITIONS = 1000;
    public static final int RPE_MIN = 50;
    public static final int RPE_MAX = 100;
    public static final int RPE_STEP = 5;
    public static final int PERCENT_MIN = 1;
    public static final int PERCENT_MAX = 1500;
}
