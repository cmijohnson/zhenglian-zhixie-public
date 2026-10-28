package com.yingge.cloud.release.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.release.api.ReleaseDtos;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.api.PageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductReleaseService {
    private static final TypeReference<LinkedHashMap<String, List<String>>> SECTIONS_TYPE =
            new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentActorProvider actors;
    private final String configuredVersion;

    public ProductReleaseService(JdbcTemplate jdbc, ObjectMapper objectMapper, CurrentActorProvider actors,
                                 @Value("${app.release.version}") String configuredVersion) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.actors = actors;
        this.configuredVersion = configuredVersion;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReleaseDtos.Response> list(int page, int size) {
        UUID userId = actors.require().userId();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        long total = jdbc.queryForObject("select count(*) from product_releases where published", Long.class);
        List<ReleaseDtos.Response> items = jdbc.query("""
                select r.id, r.version, r.title, r.summary, r.sections::text, r.released_at, r.current,
                       exists(select 1 from product_release_acknowledgements a
                              where a.release_id=r.id and a.user_id=?) acknowledged
                from product_releases r
                where r.published
                order by r.released_at desc, r.version desc
                limit ? offset ?
                """, this::map, userId, safeSize, safePage * safeSize);
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) safeSize);
        return new PageResponse<>(items, safePage, safeSize, total, totalPages);
    }

    @Transactional(readOnly = true)
    public ReleaseDtos.Response current() {
        return currentFor(actors.require().userId());
    }

    @Transactional(readOnly = true)
    public ReleaseDtos.Response unacknowledgedCurrent() {
        ReleaseDtos.Response current = currentFor(actors.require().userId());
        return current.acknowledged() ? null : current;
    }

    @Transactional
    public void acknowledge(UUID releaseId) {
        UUID userId = actors.require().userId();
        Integer exists = jdbc.queryForObject(
                "select count(*) from product_releases where id=? and published", Integer.class, releaseId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("RELEASE_NOT_FOUND", "更新记录不存在");
        }
        jdbc.update("""
                insert into product_release_acknowledgements(release_id, user_id, acknowledged_at)
                values (?, ?, now())
                on conflict (release_id, user_id) do nothing
                """, releaseId, userId);
    }

    private ReleaseDtos.Response currentFor(UUID userId) {
        List<ReleaseDtos.Response> rows = jdbc.query("""
                select r.id, r.version, r.title, r.summary, r.sections::text, r.released_at, r.current,
                       exists(select 1 from product_release_acknowledgements a
                              where a.release_id=r.id and a.user_id=?) acknowledged
                from product_releases r
                where r.published and r.current and r.version=?
                """, this::map, userId, configuredVersion);
        if (rows.size() != 1) {
            throw ApiException.unavailable("RELEASE_CATALOG_MISMATCH", "当前版本更新说明尚未正确发布");
        }
        return rows.get(0);
    }

    private ReleaseDtos.Response map(ResultSet rows, int rowNumber) throws SQLException {
        return new ReleaseDtos.Response(
                rows.getObject("id", UUID.class),
                rows.getString("version"),
                rows.getString("title"),
                rows.getString("summary"),
                sections(rows.getString("sections")),
                rows.getTimestamp("released_at").toInstant(),
                rows.getBoolean("current"),
                rows.getBoolean("acknowledged"));
    }

    private Map<String, List<String>> sections(String json) {
        try {
            return objectMapper.readValue(json, SECTIONS_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid product release sections", exception);
        }
    }
}
