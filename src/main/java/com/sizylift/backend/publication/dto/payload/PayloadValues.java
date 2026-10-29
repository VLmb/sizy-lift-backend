package com.sizylift.backend.publication.dto.payload;

/** Допустимые значения и пределы payload v1; перечисления совпадают с CHECK-ограничениями SQLite в приложении. */
final class PayloadValues {

    static final String RESULT_TYPE = "weight_reps|added_weight_reps|duration|distance_time|free";
    static final String SET_TYPE = "warmup|working|top|backoff|drop";
    static final String BLOCK_TYPE = "single|group|circuit|interval|free";
    static final String GROUP_LABEL = "superset|triset|giant_set";
    static final String FREE_KIND = "warmup|cooldown|mobility";
    static final String SEX = "female|male|other|unspecified";
    static final String MUSCLE_ROLE = "primary|secondary";
    static final String INJURY_STATUS = "active|past";
    static final String PROGRAM_STATUS = "draft|active";
    static final String WORKOUT_QUALITY = "bad|okay|great";
    static final String SET_COMPLETION = "pending|completed|skipped";
    static final String RECORD_TYPE = "maximum_weight|estimated_one_rep_max";

    static final int SHORT_TEXT = 200;
    static final int LONG_TEXT = 4000;

    /** RPE хранится в десятых долях: 5.0–10.0. */
    static final int RPE_TENTHS_MIN = 50;
    static final int RPE_TENTHS_MAX = 100;

    /** Процент от ПМ в плановом подходе хранится в десятых долях: 0,1–150 %. */
    static final int PERCENT_ONE_RM_TENTHS_MIN = 1;
    static final int PERCENT_ONE_RM_TENTHS_MAX = 1500;

    static final int MAX_SPORT_DIRECTIONS = 20;
    static final int MAX_MUSCLES = 30;
    static final int MAX_EQUIPMENT = 30;
    static final int MAX_CYCLES = 50;
    /** Два года по неделе. */
    static final int MAX_WEEKS_PER_CYCLE = 104;
    /** До двух тренировок в день. */
    static final int MAX_WORKOUTS_PER_WEEK = 14;
    static final int MAX_BLOCKS = 100;
    static final int MAX_EXERCISES_PER_BLOCK = 50;
    static final int MAX_SETS_PER_EXERCISE = 200;
    static final int MAX_PLAN_SNAPSHOTS = 2000;
    static final int MAX_RECORD_EVENTS = 2000;

    private PayloadValues() {
    }
}
