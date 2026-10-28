package com.yingge.cloud.qq.infrastructure;

import com.yingge.cloud.shared.api.ApiException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class QqWorkbenchRoutePolicy {
    public record Capability(boolean writesData, boolean downloadsData) {}
    private record Route(HttpMethod method, Pattern path, Capability capability) {}

    private static Route read(String regex) { return new Route(HttpMethod.GET, Pattern.compile(regex), new Capability(false, false)); }
    private static Route read(HttpMethod method, String regex) { return new Route(method, Pattern.compile(regex), new Capability(false, false)); }
    private static Route download(String regex) { return new Route(HttpMethod.GET, Pattern.compile(regex), new Capability(false, true)); }
    private static Route write(HttpMethod method, String regex) { return new Route(method, Pattern.compile(regex), new Capability(true, false)); }

    private final List<Route> routes = List.of(
            read("^/(?:qce(?:/.*)?|static/qce/.*|health|security-status)$"),
            read("^/(?:text-logo\\.png|text-full-logo\\.png|placeholder-logo\\.(?:png|svg)|placeholder-user\\.jpg|placeholder\\.(?:jpg|svg)|_vercel/insights/script\\.js)$"),
            download("^/(?:downloads|scheduled-downloads|resources)/[^\\p{Cntrl}?#\\\\]+$"),
            read("^/api/(?:system/(?:info|status)|config|groups|group-system-notify|friends|recent-contacts|users/lookup|tasks|scheduled-exports|sticker-packs|sticker-packs/export-records|group-albums/export-records|group-files/export-records|exports/files|resources/(?:index|files)|merge-resources/available-tasks)$"),
            read("^/api/groups/[^/]+(?:/members|/join-requests|/essence|/albums|/files|/files/count)?$"),
            read("^/api/groups/[^/]+/albums/[^/]+/media$"),
            read("^/api/friends/[^/]+$"),
            read("^/api/users/[^/]+$"),
            read("^/api/tasks/[^/]+$"),
            read("^/api/scheduled-exports/[^/]+(?:/history)?$"),
            read("^/api/exports/files/[^/]+/(?:info|preview)$"),
            download("^/api/exports/files/[^/]+/resources/[A-Za-z0-9._~!$&'()+,;=:@%/-]+$"),
            download("^/api/resources/export/[^/]+$"),
            read(HttpMethod.POST, "^/api/messages/fetch$"),
            write(HttpMethod.POST, "^/api/messages/(?:export|export-streaming-zip|export-streaming-jsonl)$"),
            write(HttpMethod.POST, "^/api/groups/[^/]+/(?:essence/export|avatars/export|albums/export|files/(?:download|export|export-with-download))$"),
            write(HttpMethod.POST, "^/api/tasks/[^/]+/cancel$"),
            write(HttpMethod.DELETE, "^/api/tasks/[^/]+(?:/original-files)?$"),
            write(HttpMethod.POST, "^/api/scheduled-exports$"),
            write(HttpMethod.POST, "^/api/scheduled-exports/(?:trigger-all|trigger-batch|update-batch|delete-batch)$"),
            write(HttpMethod.PUT, "^/api/scheduled-exports/[^/]+$"),
            write(HttpMethod.DELETE, "^/api/scheduled-exports/[^/]+$"),
            write(HttpMethod.POST, "^/api/scheduled-exports/[^/]+/trigger$"),
            write(HttpMethod.POST, "^/api/sticker-packs/(?:export|export-all)$"),
            write(HttpMethod.DELETE, "^/api/exports/files/[^/]+$"),
            write(HttpMethod.POST, "^/api/merge-resources$"),
            write(HttpMethod.PUT, "^/api/config$")
    );

    public Capability requireAllowed(String methodValue, String upstreamPath) {
        return requireAllowed(methodValue, upstreamPath, false);
    }

    public Capability requireAllowedMediaSource(String upstreamPath) {
        String path = validatedPath(upstreamPath);
        if (path.equals("/api/download-file")) {
            return new Capability(false, true);
        }
        return requireAllowed("GET", upstreamPath, true);
    }

    private Capability requireAllowed(String methodValue, String upstreamPath, boolean mediaSource) {
        String path = validatedPath(upstreamPath);
        HttpMethod method = HttpMethod.valueOf(methodValue.toUpperCase());
        Capability capability = routes.stream()
                .filter(route -> route.method().equals(method) && route.path().matcher(path).matches())
                .findFirst().map(Route::capability)
                .orElseThrow(() -> ApiException.forbidden("该 QQ 工作台能力未开放"));
        if (mediaSource && !capability.downloadsData()) {
            throw ApiException.forbidden("该 QQ 媒体来源未开放");
        }
        return capability;
    }

    private String validatedPath(String upstreamPath) {
        if (upstreamPath == null || !upstreamPath.startsWith("/") || upstreamPath.length() > 2048
                || upstreamPath.indexOf('\\') >= 0 || upstreamPath.indexOf('\0') >= 0) {
            throw ApiException.badRequest("QQ_WORKBENCH_PATH_INVALID", "QQ 工作台路径无效");
        }
        String decoded;
        try { decoded = URLDecoder.decode(upstreamPath, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("QQ_WORKBENCH_PATH_INVALID", "QQ 工作台路径编码无效");
        }
        String path = decoded.split("\\?", 2)[0];
        for (String part : path.split("/")) {
            if (part.equals(".") || part.equals("..")) {
                throw ApiException.badRequest("QQ_WORKBENCH_PATH_INVALID", "QQ 工作台路径无效");
            }
        }
        return path;
    }
}
