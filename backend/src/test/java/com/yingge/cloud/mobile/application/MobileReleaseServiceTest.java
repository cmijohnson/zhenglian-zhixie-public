package com.yingge.cloud.mobile.application;

import com.yingge.cloud.mobile.api.MobileDtos;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MobileReleaseServiceTest {
    @Test
    void currentNeverOffersAnInstalledOrOlderVersion() {
        var jdbc = new CapturingJdbcTemplate();
        var service = new MobileReleaseService(jdbc, "/tmp/mobile-release-test");

        assertThat(service.current(MobileDtos.Platform.ANDROID, 4)).isNull();
        assertThat(jdbc.sql).contains("version_code>?");
        assertThat(jdbc.arguments).containsExactly("ANDROID", 4L);
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] arguments;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.arguments = args;
            return List.of();
        }
    }
}
