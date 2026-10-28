package com.yingge.cloud.intelligence.application;

import com.zaxxer.hikari.HikariDataSource;
import com.yingge.cloud.intelligence.api.CustomerIntelligenceDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class CustomerIntelligenceRuntimeMonitor {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public CustomerIntelligenceRuntimeMonitor(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public CustomerIntelligenceDtos.RuntimeStatus snapshot(UUID organizationId) {
        QueueSnapshot queue = jdbc.queryForObject("""
                select count(*) filter (where status='PENDING') pending_jobs,
                       count(*) filter (where status='RUNNING') running_jobs,
                       count(*) filter (where status='FAILED' and updated_at>=now()-interval '24 hours') failed_jobs,
                       extract(epoch from (now()-min(created_at) filter (where status='PENDING')))::bigint
                         oldest_pending_seconds
                from customer_analysis_jobs where organization_id=?
                """, (row, index) -> new QueueSnapshot(
                row.getLong("pending_jobs"), row.getLong("running_jobs"), row.getLong("failed_jobs"),
                row.getObject("oldest_pending_seconds", Long.class)), organizationId);
        if (queue == null) queue = new QueueSnapshot(0, 0, 0, null);

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long heapUsed = Math.max(0, memory.getHeapMemoryUsage().getUsed());
        long heapMax = Math.max(0, memory.getHeapMemoryUsage().getMax());
        Double heapPercent = percentage(heapUsed, heapMax);

        Double systemCpu = null;
        Double processCpu = null;
        var baseOs = ManagementFactory.getOperatingSystemMXBean();
        if (baseOs instanceof com.sun.management.OperatingSystemMXBean os) {
            systemCpu = cpuPercentage(os.getCpuLoad());
            processCpu = cpuPercentage(os.getProcessCpuLoad());
        }
        SystemMemorySnapshot systemMemory = systemMemorySnapshot(baseOs);

        PoolSnapshot pool = poolSnapshot();
        Pressure pressure = evaluatePressure(new PressureInputs(
                maximum(systemCpu, processCpu), systemMemory.percent(), heapPercent, pool.pressurePercent(),
                pool.awaiting(), queue.pending(), queue.failed(), queue.oldestPendingSeconds()));
        return new CustomerIntelligenceDtos.RuntimeStatus(
                Instant.now(), pressure.level(), pressure.label(), pressure.message(),
                Runtime.getRuntime().availableProcessors(), systemCpu, processCpu,
                systemMemory.used(), systemMemory.max(), systemMemory.percent(),
                heapUsed, heapMax, heapPercent, ManagementFactory.getThreadMXBean().getThreadCount(),
                Math.max(0, ManagementFactory.getRuntimeMXBean().getUptime() / 1000),
                pool.active(), pool.idle(), pool.total(), pool.awaiting(), pool.max(), pool.pressurePercent(),
                queue.pending(), queue.running(), queue.failed(), queue.oldestPendingSeconds());
    }

    private static SystemMemorySnapshot systemMemorySnapshot(
            java.lang.management.OperatingSystemMXBean baseOs) {
        try {
            SystemMemorySnapshot linux = parseLinuxMemory(Files.readAllLines(Path.of("/proc/meminfo")));
            if (linux.max() != null) return linux;
        } catch (Exception ignored) {
            // Non-Linux runtime or restricted procfs: use the JVM operating-system bean below.
        }
        if (baseOs instanceof com.sun.management.OperatingSystemMXBean os) {
            long total = os.getTotalMemorySize();
            long free = os.getFreeMemorySize();
            if (total > 0 && free >= 0) {
                long used = Math.max(0, total - free);
                return new SystemMemorySnapshot(used, total, percentage(used, total));
            }
        }
        return new SystemMemorySnapshot(null, null, null);
    }

    static SystemMemorySnapshot parseLinuxMemory(List<String> lines) {
        long totalKilobytes = -1;
        long availableKilobytes = -1;
        for (String line : lines) {
            String[] pair = line.split(":", 2);
            if (pair.length != 2) continue;
            String rawValue = pair[1].trim().split("\\s+", 2)[0];
            try {
                if ("MemTotal".equals(pair[0])) totalKilobytes = Long.parseLong(rawValue);
                if ("MemAvailable".equals(pair[0])) availableKilobytes = Long.parseLong(rawValue);
            } catch (NumberFormatException ignored) {
                // Ignore an unreadable procfs field and fall back to the JVM metric.
            }
        }
        if (totalKilobytes <= 0 || availableKilobytes < 0) {
            return new SystemMemorySnapshot(null, null, null);
        }
        long max = totalKilobytes * 1024;
        long used = Math.max(0, max - Math.min(max, availableKilobytes * 1024));
        return new SystemMemorySnapshot(used, max, percentage(used, max));
    }

    private PoolSnapshot poolSnapshot() {
        try {
            HikariDataSource hikari = dataSource.isWrapperFor(HikariDataSource.class)
                    ? dataSource.unwrap(HikariDataSource.class) : null;
            if (hikari == null || hikari.getHikariPoolMXBean() == null) return PoolSnapshot.unavailable();
            var pool = hikari.getHikariPoolMXBean();
            int max = hikari.getMaximumPoolSize();
            int active = pool.getActiveConnections();
            return new PoolSnapshot(active, pool.getIdleConnections(), pool.getTotalConnections(),
                    pool.getThreadsAwaitingConnection(), max, percentage(active, max));
        } catch (Exception ignored) {
            return PoolSnapshot.unavailable();
        }
    }

    static Pressure evaluatePressure(PressureInputs input) {
        boolean high = atLeast(input.cpuPercent(), 90) || atLeast(input.systemMemoryPercent(), 92)
                || atLeast(input.heapPercent(), 90) || atLeast(input.dbPressurePercent(), 90)
                || positive(input.dbAwaiting()) || input.pendingJobs() >= 100 || input.failedJobs() >= 10
                || atLeast(input.oldestPendingSeconds(), 1800);
        if (high) return new Pressure("HIGH", "高压", "资源或分析队列压力较高，建议暂停批量回填并检查任务失败原因。");
        boolean busy = atLeast(input.cpuPercent(), 65) || atLeast(input.systemMemoryPercent(), 78)
                || atLeast(input.heapPercent(), 72) || atLeast(input.dbPressurePercent(), 70)
                || input.pendingJobs() >= 25 || input.failedJobs() > 0
                || atLeast(input.oldestPendingSeconds(), 300);
        if (busy) return new Pressure("BUSY", "较忙", "服务仍可用，但资源或分析队列已出现持续占用，请留意趋势。");
        return new Pressure("NORMAL", "正常", "应用实例、数据库连接池和客户分析队列处于正常范围。");
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static boolean atLeast(Number value, double threshold) {
        return value != null && value.doubleValue() >= threshold;
    }

    private static Double maximum(Double first, Double second) {
        if (first == null) return second;
        if (second == null) return first;
        return Math.max(first, second);
    }

    private static Double cpuPercentage(double ratio) {
        return ratio < 0 || Double.isNaN(ratio) ? null : round(ratio * 100);
    }

    private static Double percentage(long used, long max) {
        return max <= 0 ? null : round(used * 100d / max);
    }

    private static double round(double value) {
        return Math.round(value * 10d) / 10d;
    }

    record PressureInputs(Double cpuPercent, Double systemMemoryPercent, Double heapPercent,
                          Double dbPressurePercent, Integer dbAwaiting, long pendingJobs,
                          long failedJobs, Long oldestPendingSeconds) {}

    record Pressure(String level, String label, String message) {}

    private record QueueSnapshot(long pending, long running, long failed, Long oldestPendingSeconds) {}

    record SystemMemorySnapshot(Long used, Long max, Double percent) {}

    private record PoolSnapshot(Integer active, Integer idle, Integer total, Integer awaiting,
                                Integer max, Double pressurePercent) {
        private static PoolSnapshot unavailable() {
            return new PoolSnapshot(null, null, null, null, null, null);
        }
    }
}
