package com.sizylift.backend.photo.dto;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkoutPhotoDtos {
    private WorkoutPhotoDtos() {
    }

    public record Metadata(UUID workoutId, UUID version, boolean deleted, Integer width, Integer height,
                           Integer byteSize, Instant updatedAt) {
    }

    public record Page(List<Metadata> items, UUID nextCursor) {
    }

    public record View(Metadata photo, URI url) {
    }

    public record WriteResult(UUID version, boolean accepted) {
    }
}
