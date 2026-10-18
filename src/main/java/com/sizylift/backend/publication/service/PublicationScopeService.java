package com.sizylift.backend.publication.service;

import java.util.UUID;

import com.sizylift.backend.publication.repository.AthleteDataRepository;
import com.sizylift.backend.publication.repository.ProgramDataRepository;
import com.sizylift.backend.publication.repository.PublicationScopeRepository;
import com.sizylift.backend.publication.repository.PublishedAggregateRepository;
import com.sizylift.backend.publication.repository.WorkoutDataRepository;
import com.sizylift.backend.publication.service.PublicationScope.Programs;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User's publication settings. Narrowing them deletes what is no longer allowed. */
@Service
public class PublicationScopeService {

    private final PublicationScopeRepository scopes;
    private final PublishedAggregateRepository versions;
    private final WorkoutDataRepository workouts;
    private final ProgramDataRepository programs;
    private final AthleteDataRepository athlete;

    public PublicationScopeService(PublicationScopeRepository scopes, PublishedAggregateRepository versions,
                                   WorkoutDataRepository workouts, ProgramDataRepository programs,
                                   AthleteDataRepository athlete) {
        this.scopes = scopes;
        this.versions = versions;
        this.workouts = workouts;
        this.programs = programs;
        this.athlete = athlete;
    }

    @Transactional(readOnly = true)
    public PublicationScope get(UUID userId) {
        return scopes.find(userId).orElse(PublicationScope.DEFAULT);
    }

    /** Saves the scope and removes data outside it. The app then republishes everything it has. */
    @Transactional
    public PublicationScope update(UUID userId, PublicationScope scope) {
        scopes.save(userId, scope);
        if (!scope.enabled()) {
            deleteEverything(userId);
            return scope;
        }
        if (!scope.workouts()) {
            workouts.deleteAll(userId);
            versions.forgetType(userId, "workout");
        } else if (scope.workoutsSince() != null) {
            workouts.deleteBefore(userId, scope.workoutsSince());
        }
        if (scope.programs() == Programs.NONE) {
            programs.deleteAll(userId);
            versions.forgetType(userId, "program");
        } else if (scope.programs() == Programs.ACTIVE) {
            programs.deleteInactive(userId);
        }
        if (!scope.allowsExercises()) {
            athlete.deleteExercises(userId);
            versions.forgetType(userId, "exercise");
        }
        if (!scope.profile()) {
            athlete.deleteProfile(userId);
            versions.forgetType(userId, "athlete_profile");
        }
        if (!scope.records()) {
            athlete.deleteOneRepMaxes(userId);
            versions.forgetType(userId, "one_rep_max");
        }
        if (!scope.health()) {
            athlete.deleteHealth(userId);
            versions.forgetType(userId, "injury");
            versions.forgetType(userId, "contraindication");
        }
        return scope;
    }

    /** "Delete my data from the server": removes everything and stops accepting publications. */
    @Transactional
    public PublicationScope disableAndDeleteAll(UUID userId) {
        var disabled = new PublicationScope(false, PublicationScope.DEFAULT.workouts(), null,
                PublicationScope.DEFAULT.programs(), PublicationScope.DEFAULT.profile(),
                PublicationScope.DEFAULT.records(), false);
        scopes.save(userId, disabled);
        deleteEverything(userId);
        return disabled;
    }

    private void deleteEverything(UUID userId) {
        workouts.deleteAll(userId);
        programs.deleteAll(userId);
        athlete.deleteExercises(userId);
        athlete.deleteProfile(userId);
        athlete.deleteOneRepMaxes(userId);
        athlete.deleteHealth(userId);
        for (var type : new String[] {"workout", "program", "exercise", "athlete_profile", "one_rep_max", "injury",
                "contraindication"}) {
            versions.forgetType(userId, type);
        }
    }
}
