package com.sizylift.backend.publication.dto.payload;

/** Allowed values shared with the app's SQLite CHECK constraints. */
final class PayloadValues {

    static final String RESULT_TYPE = "weight_reps|added_weight_reps|duration|distance_time|free";
    static final String SET_TYPE = "warmup|working|top|backoff|drop";
    static final String BLOCK_TYPE = "single|group|circuit|interval|free";
    static final String GROUP_LABEL = "superset|triset|giant_set";
    static final String FREE_KIND = "warmup|cooldown|mobility";
    static final int SHORT_TEXT = 200;
    static final int LONG_TEXT = 4000;

    private PayloadValues() {
    }
}
