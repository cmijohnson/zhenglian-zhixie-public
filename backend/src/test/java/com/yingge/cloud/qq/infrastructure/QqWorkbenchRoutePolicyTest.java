package com.yingge.cloud.qq.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QqWorkbenchRoutePolicyTest {
    private final QqWorkbenchRoutePolicy policy = new QqWorkbenchRoutePolicy();

    @Test
    void allowsListedReadWriteAndDownloadCapabilities() {
        assertThat(policy.requireAllowed("GET", "/api/friends?page=2&limit=100").writesData()).isFalse();
        assertThat(policy.requireAllowed("POST", "/api/messages/fetch").writesData()).isFalse();
        assertThat(policy.requireAllowed("POST", "/api/messages/export").writesData()).isTrue();
        assertThat(policy.requireAllowed("GET", "/downloads/export-1.zip").downloadsData()).isTrue();
        assertThat(policy.requireAllowed("GET", "/downloads/群聊_测试记录_20260814.json").downloadsData()).isTrue();
        assertThat(policy.requireAllowed("GET", "/api/groups/123/members").writesData()).isFalse();
    }

    @Test
    void coversEveryBusinessRouteShapeFromThePinnedUpstreamRouter() {
        String[] reads = {
                "/api/system/info", "/api/system/status", "/api/config", "/api/groups",
                "/api/groups/123", "/api/groups/123/members", "/api/group-system-notify",
                "/api/groups/123/join-requests", "/api/groups/123/essence", "/api/friends",
                "/api/friends/u-1", "/api/recent-contacts", "/api/users/lookup", "/api/users/u-1",
                "/api/tasks", "/api/tasks/t-1", "/api/scheduled-exports",
                "/api/scheduled-exports/s-1", "/api/scheduled-exports/s-1/history",
                "/api/sticker-packs", "/api/sticker-packs/export-records",
                "/api/groups/123/albums", "/api/groups/123/albums/a-1/media",
                "/api/group-albums/export-records", "/api/groups/123/files",
                "/api/groups/123/files/count", "/api/group-files/export-records",
                "/api/exports/files", "/api/exports/files/export.zip/info",
                "/api/exports/files/export.zip/preview", "/api/resources/index",
                "/api/resources/files", "/api/merge-resources/available-tasks"
        };
        for (String path : reads) {
            assertThat(policy.requireAllowed("GET", path).writesData()).as(path).isFalse();
        }

        String[] writes = {
                "/api/messages/export", "/api/messages/export-streaming-zip",
                "/api/messages/export-streaming-jsonl", "/api/groups/123/essence/export",
                "/api/groups/123/avatars/export", "/api/groups/123/albums/export",
                "/api/groups/123/files/download", "/api/groups/123/files/export",
                "/api/groups/123/files/export-with-download", "/api/tasks/t-1/cancel",
                "/api/scheduled-exports", "/api/scheduled-exports/trigger-all",
                "/api/scheduled-exports/trigger-batch", "/api/scheduled-exports/update-batch",
                "/api/scheduled-exports/delete-batch", "/api/scheduled-exports/s-1/trigger",
                "/api/sticker-packs/export", "/api/sticker-packs/export-all", "/api/merge-resources"
        };
        for (String path : writes) {
            assertThat(policy.requireAllowed("POST", path).writesData()).as(path).isTrue();
        }
        assertThat(policy.requireAllowed("PUT", "/api/config").writesData()).isTrue();
        assertThat(policy.requireAllowed("PUT", "/api/scheduled-exports/s-1").writesData()).isTrue();
        assertThat(policy.requireAllowed("DELETE", "/api/tasks/t-1").writesData()).isTrue();
        assertThat(policy.requireAllowed("DELETE", "/api/tasks/t-1/original-files").writesData()).isTrue();
        assertThat(policy.requireAllowed("DELETE", "/api/scheduled-exports/s-1").writesData()).isTrue();
        assertThat(policy.requireAllowed("DELETE", "/api/exports/files/export.zip").writesData()).isTrue();

        assertThat(policy.requireAllowed("GET", "/api/exports/files/export.zip/resources/images/a.png").downloadsData()).isTrue();
        assertThat(policy.requireAllowed("GET", "/api/resources/export/export.zip").downloadsData()).isTrue();
    }

    @Test
    void blocksPlatformControlPlaneUnknownPathsAndTraversal() {
        assertThatThrownBy(() -> policy.requireAllowed("POST", "/auth"))
                .hasMessageContaining("未开放");
        assertThatThrownBy(() -> policy.requireAllowed("POST", "/api/server/host"))
                .hasMessageContaining("未开放");
        assertThatThrownBy(() -> policy.requireAllowed("GET", "/api/security/ip-whitelist"))
                .hasMessageContaining("未开放");
        assertThatThrownBy(() -> policy.requireAllowed("POST", "/api/open-export-directory"))
                .hasMessageContaining("未开放");
        assertThatThrownBy(() -> policy.requireAllowed("POST", "/api/open-file-location"))
                .hasMessageContaining("未开放");
        assertThatThrownBy(() -> policy.requireAllowed("GET", "/api/server/config"))
                .hasMessageContaining("未开放");
        assertThatThrownBy(() -> policy.requireAllowed("GET", "/api/download-file?path=/etc/passwd"))
                .hasMessageContaining("未开放");
        assertThatThrownBy(() -> policy.requireAllowed("GET", "/downloads/%2e%2e/secret"))
                .hasMessageContaining("路径无效");
        assertThatThrownBy(() -> policy.requireAllowed("GET", "/downloads/群聊\\security.json"))
                .hasMessageContaining("路径无效");
    }

    @Test
    void onlyAllowsContainerPathsAfterAnOpaqueMediaTicketWasValidated() {
        assertThat(policy.requireAllowedMediaSource("/api/download-file?path=%2Fdata%2Fmessage.png").downloadsData())
                .isTrue();
        assertThat(policy.requireAllowedMediaSource("/downloads/export-1.zip").downloadsData()).isTrue();
        assertThatThrownBy(() -> policy.requireAllowedMediaSource("/api/friends"))
                .hasMessageContaining("媒体来源未开放");
    }
}
