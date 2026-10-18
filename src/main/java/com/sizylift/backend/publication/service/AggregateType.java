package com.sizylift.backend.publication.service;

/** Коды типов агрегатов в пачке публикации (docs/publication.md, «Агрегаты»). */
public final class AggregateType {

    public static final String WORKOUT = "workout";
    public static final String PROGRAM = "program";
    public static final String EXERCISE = "exercise";
    public static final String ATHLETE_PROFILE = "athlete_profile";
    public static final String ONE_REP_MAX = "one_rep_max";
    public static final String INJURY = "injury";
    public static final String CONTRAINDICATION = "contraindication";

    private AggregateType() {
    }
}
