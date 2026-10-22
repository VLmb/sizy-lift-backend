package com.sizylift.backend.publication.service;

import java.util.Set;

/** Коды типов агрегатов (docs/publication.md, «Агрегаты»; docs/coaching.md). */
public final class AggregateType {

    public static final String WORKOUT = "workout";
    public static final String PROGRAM = "program";
    public static final String EXERCISE = "exercise";
    public static final String ATHLETE_PROFILE = "athlete_profile";
    public static final String ONE_REP_MAX = "one_rep_max";
    public static final String INJURY = "injury";
    public static final String CONTRAINDICATION = "contraindication";
    /** Перенос тренировки подопечным; только sync v2 (docs/coaching.md). */
    public static final String WORKOUT_RESCHEDULE = "workout_reschedule";
    /** Комментарий к тренировке; только sync v2. */
    public static final String WORKOUT_COMMENT = "workout_comment";

    /** Типы публикации v1: старое приложение других не шлёт, а правила новых типов есть только в sync v2. */
    public static final Set<String> V1 = Set.of(WORKOUT, PROGRAM, EXERCISE, ATHLETE_PROFILE, ONE_REP_MAX, INJURY,
            CONTRAINDICATION);

    private AggregateType() {
    }
}
