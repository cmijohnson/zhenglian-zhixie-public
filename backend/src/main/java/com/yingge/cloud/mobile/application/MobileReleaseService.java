package com.yingge.cloud.mobile.application;

import com.yingge.cloud.mobile.api.MobileDtos;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
public class MobileReleaseService {
    private final JdbcTemplate jdbc;
    private final Path releaseDirectory;

    public MobileReleaseService(JdbcTemplate jdbc, @Value("${app.mobile.release-dir:/data/mobile-releases}") String directory) {
        this.jdbc = jdbc;
        this.releaseDirectory = Path.of(directory).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public MobileDtos.AppRelease current(MobileDtos.Platform platform, long installedVersionCode) {
        List<MobileDtos.AppRelease> rows = jdbc.query("""
                select id,platform,version_code,version_name,minimum_supported_version_code,mandatory,notes,
                       apk_size,apk_sha256,manifest_signature
                from mobile_app_releases
                where platform=? and published=true and version_code>?
                order by version_code desc limit 1
                """, (rs, row) -> new MobileDtos.AppRelease(
                rs.getObject("id", UUID.class), MobileDtos.Platform.valueOf(rs.getString("platform")),
                rs.getLong("version_code"), rs.getString("version_name"),
                rs.getLong("minimum_supported_version_code"),
                rs.getBoolean("mandatory") || installedVersionCode < rs.getLong("minimum_supported_version_code"),
                rs.getString("notes"), rs.getObject("apk_size", Long.class), rs.getString("apk_sha256"),
                rs.getString("manifest_signature")), platform.name(), installedVersionCode);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional(readOnly = true)
    public Apk apk(UUID releaseId) {
        List<ApkRow> rows = jdbc.query("""
                select platform,apk_path,apk_size,apk_sha256 from mobile_app_releases
                where id=? and published=true
                """, (rs, row) -> new ApkRow(rs.getString("platform"), rs.getString("apk_path"),
                rs.getObject("apk_size", Long.class), rs.getString("apk_sha256")), releaseId);
        if (rows.isEmpty() || !"ANDROID".equals(rows.getFirst().platform())
                || rows.getFirst().path() == null || rows.getFirst().path().isBlank()) {
            throw ApiException.notFound("MOBILE_APK_NOT_FOUND", "Android 安装包不存在");
        }
        Path relative = Path.of(rows.getFirst().path());
        if (relative.isAbsolute()) throw ApiException.notFound("MOBILE_APK_NOT_FOUND", "Android 安装包不存在");
        Path file = releaseDirectory.resolve(relative).normalize();
        if (!file.startsWith(releaseDirectory) || !Files.isRegularFile(file)) {
            throw ApiException.notFound("MOBILE_APK_NOT_FOUND", "Android 安装包不存在");
        }
        return new Apk(new FileSystemResource(file), rows.getFirst().size(), rows.getFirst().sha256());
    }

    public record Apk(Resource resource, Long size, String sha256) {}
    private record ApkRow(String platform, String path, Long size, String sha256) {}
}
