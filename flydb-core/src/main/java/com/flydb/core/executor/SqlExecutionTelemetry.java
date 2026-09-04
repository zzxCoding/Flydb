package com.flydb.core.executor;

import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import com.flydb.core.log.Log;

/** 单份 SQL 脚本的内存执行遥测；不持久化，也不推断事务提交状态。 */
final class SqlExecutionTelemetry {

    private static final ScheduledThreadPoolExecutor REPORTER = createReporter();

    private final String scriptName;
    private Log log;
    private LongSupplier nanoClock;
    private long reportIntervalNanos;
    private int reportStatementInterval;
    private int total;
    private int confirmed;
    private int lastReportedCount;
    private long startedNanos;
    private long lastReportedNanos;
    private String failureLocation;
    private boolean started;
    private boolean active;
    private boolean reported;
    private ScheduledFuture<?> scheduledReport;

    SqlExecutionTelemetry(String scriptName) {
        this.scriptName = scriptName;
    }

    void configure(Log log, LongSupplier nanoClock,
                   long reportIntervalNanos, int reportStatementInterval) {
        if (log == null) throw new NullPointerException("log must not be null");
        if (nanoClock == null) throw new NullPointerException("nanoClock must not be null");
        if (reportIntervalNanos <= 0L) {
            throw new IllegalArgumentException("reportIntervalNanos must be > 0");
        }
        if (reportStatementInterval <= 0) {
            throw new IllegalArgumentException("reportStatementInterval must be > 0");
        }
        this.log = log;
        this.nanoClock = nanoClock;
        this.reportIntervalNanos = reportIntervalNanos;
        this.reportStatementInterval = reportStatementInterval;
    }

    synchronized void start(int totalStatements) {
        cancelScheduledReport();
        total = totalStatements;
        confirmed = 0;
        lastReportedCount = 0;
        failureLocation = null;
        started = true;
        active = true;
        reported = false;
        if (log != null) {
            startedNanos = nanoClock.getAsLong();
            lastReportedNanos = startedNanos;
            if (total > 0) {
                try {
                    scheduledReport = REPORTER.scheduleAtFixedRate(
                            new Runnable() {
                                @Override
                                public void run() {
                                    reportPeriodically();
                                }
                            }, reportIntervalNanos, reportIntervalNanos,
                            TimeUnit.NANOSECONDS);
                } catch (RuntimeException ignored) {
                    // 无法创建后台心跳时保留同步计数阈值，不能阻断迁移。
                }
            }
        }
    }

    synchronized void confirm(int statementCount) {
        confirm(statementCount, true);
    }

    synchronized void confirmWithoutProgress(int statementCount) {
        confirm(statementCount, false);
    }

    private void confirm(int statementCount, boolean report) {
        if (statementCount <= 0) return;
        confirmed = Math.min(total, confirmed + statementCount);
        if (!report || log == null || total == 0) return;
        long now = nanoClock.getAsLong();
        boolean dueByCount = confirmed - lastReportedCount >= reportStatementInterval;
        boolean dueByTime = elapsed(now, lastReportedNanos) >= reportIntervalNanos;
        boolean completesReportedMigration = confirmed == total
                && reported && lastReportedCount != confirmed;
        if (dueByCount || dueByTime || completesReportedMigration) safeReport(now);
    }

    synchronized void failExact(int index, int lineNumber, String evidence) {
        failureLocation = "第 " + index + " 条（起始行 " + lineNumber + "，" + evidence + "）";
    }

    synchronized void failInferred(int index, int lineNumber) {
        failureLocation = "按 JDBC 已返回计数推算为第 " + index + " 条（起始行 "
                + lineNumber + "），不是驱动明确失败标记";
    }

    synchronized void failRange(int startIndex, int endIndex) {
        failureLocation = "无法可靠定位具体语句，候选批次为第 "
                + startIndex + "-" + endIndex + " 条";
    }

    synchronized String snapshot() {
        if (!started) return "JDBC 语句执行尚未开始";
        StringBuilder result = new StringBuilder(160)
                .append("JDBC 已确认执行 ").append(confirmed).append('/').append(total)
                .append(" 条（仅表示从脚本开头连续收到成功返回，不代表事务已提交）");
        if (failureLocation != null) {
            result.append("；失败定位：").append(failureLocation);
        } else if (total > 0 && confirmed == total) {
            result.append("；语句状态：全部 SQL 已由 JDBC 返回成功");
        } else {
            result.append("；失败定位：尚未进入或无法定位到具体 SQL 语句");
        }
        return result.toString();
    }

    synchronized void stop() {
        active = false;
        cancelScheduledReport();
    }

    private synchronized void reportPeriodically() {
        if (!active || log == null || total == 0) return;
        long now = nanoClock.getAsLong();
        if (elapsed(now, lastReportedNanos) >= reportIntervalNanos) safeReport(now);
    }

    private void safeReport(long now) {
        try {
            report(now);
        } catch (RuntimeException ignored) {
            // 诊断日志失败不能改变迁移结果；下个周期仍会继续尝试。
        }
    }

    private void report(long now) {
        long elapsedNanos = elapsed(now, startedNanos);
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0d;
        double rate = elapsedNanos == 0L
                ? 0.0d : confirmed * 1_000_000_000.0d / elapsedNanos;
        log.info("迁移语句进度 " + scriptName + "：JDBC 已确认执行 "
                + confirmed + "/" + total + " 条，耗时 " + decimal(elapsedSeconds)
                + " 秒，平均速率 " + decimal(rate) + " 条/秒");
        reported = true;
        lastReportedCount = confirmed;
        lastReportedNanos = now;
    }

    private void cancelScheduledReport() {
        if (scheduledReport != null) {
            scheduledReport.cancel(false);
            scheduledReport = null;
        }
    }

    private static ScheduledThreadPoolExecutor createReporter() {
        ScheduledThreadPoolExecutor reporter = new ScheduledThreadPoolExecutor(1,
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "flydb-sql-progress");
                        thread.setDaemon(true);
                        thread.setContextClassLoader(null);
                        return thread;
                    }
                });
        reporter.setRemoveOnCancelPolicy(true);
        reporter.setKeepAliveTime(30L, TimeUnit.SECONDS);
        reporter.allowCoreThreadTimeOut(true);
        return reporter;
    }

    private static long elapsed(long now, long before) {
        return now >= before ? now - before : 0L;
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
