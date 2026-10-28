package com.yingge.cloud.release.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReleaseDtos {
    private ReleaseDtos() {}

    public record Response(
            UUID id,
            String version,
            String title,
            String summary,
            Map<String, List<String>> sections,
            Instant releasedAt,
            boolean current,
            boolean acknowledged
    ) {}
}
