package com.sizylift.backend.sync.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Id копии собственного упражнения тренера в библиотеке подопечного (docs/coaching.md): производный
 * и постоянный, поэтому его вычисляет и запись программы, и видео упражнения (docs/video.md).
 */
public final class CoachExerciseCopies {

    private static final String COPY_ID_PREFIX = "sizylift:coach-exercise-copy:";

    private CoachExerciseCopies() {
    }

    public static UUID copyId(UUID athleteId, UUID coachExerciseId) {
        return UUID.nameUUIDFromBytes((COPY_ID_PREFIX + athleteId + ":" + coachExerciseId)
                .getBytes(StandardCharsets.UTF_8));
    }
}
