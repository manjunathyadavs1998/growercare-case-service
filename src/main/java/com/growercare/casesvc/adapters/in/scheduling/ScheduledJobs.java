package com.growercare.casesvc.adapters.in.scheduling;

import com.growercare.casesvc.application.AttachmentScanJob;
import com.growercare.casesvc.application.MaintenanceJobs;
import com.growercare.casesvc.application.SlaTimerPoller;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Inbound adapter: triggers SPEC-CASE-04 jobs on a schedule (D7) and publishes the default-partition gauge.
 * The bean always exists (tests call it directly); scheduling is on only with {@code gc.jobs.enabled=true}.
 * Failures are logged and retried on the next tick; they never stop the scheduler.
 */
@Component
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);
    public static final String DEFAULT_ROWS_METRIC = "gc.partition.default.rows";

    private final SlaTimerPoller poller;
    private final MaintenanceJobs maintenance;
    private final AttachmentScanJob attachments;
    private final AtomicLong defaultRows = new AtomicLong();

    public ScheduledJobs(SlaTimerPoller poller, MaintenanceJobs maintenance, AttachmentScanJob attachments, MeterRegistry meters) {
        this.poller = poller;
        this.maintenance = maintenance;
        this.attachments = attachments;
        Gauge.builder(DEFAULT_ROWS_METRIC, defaultRows, AtomicLong::get)
                .description("Rows in catch-all partitions across all shards (must be 0)")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${gc.jobs.sla-poll-delay:30s}", initialDelayString = "${gc.jobs.initial-delay:20s}")
    public void pollSlaTimers() {
        run("sla-poller", poller::runOnce);
    }

    @Scheduled(fixedDelayString = "${gc.jobs.attachment-scan-delay:10s}", initialDelayString = "${gc.jobs.initial-delay:20s}")
    public void scanAttachments() {
        run("attachment-scan", attachments::runOnce);                                         // SPEC-CASE-05 D4
    }

    @Scheduled(cron = "${gc.jobs.attachment-expiry-cron:0 50 3 * * *}")
    public void expireAttachments() {
        run("attachment-expiry", () -> attachments.expireStale(Duration.ofHours(24)));        // SPEC-CASE-05 R113
    }

    @Scheduled(cron = "${gc.jobs.partitions-cron:0 15 2 * * *}")
    public void ensurePartitions() {
        run("partitions", () -> maintenance.ensurePartitions(8));
    }

    @Scheduled(cron = "${gc.jobs.idempotency-cron:0 45 2 * * *}")
    public void cleanupIdempotency() {
        run("idempotency-cleanup", () -> maintenance.cleanupIdempotency(Duration.ofDays(7)));
    }

    @Scheduled(cron = "${gc.jobs.outbox-cleanup-cron:0 30 3 * * *}")
    public void cleanupOutbox() {
        run("outbox-cleanup", () -> maintenance.cleanupOutbox(Duration.ofDays(3)));       // SPEC-PLAT-04 R55
    }

    @Scheduled(cron = "${gc.jobs.inbox-cleanup-cron:0 40 3 * * *}")
    public void cleanupInbox() {
        run("inbox-cleanup", () -> maintenance.cleanupInbox(Duration.ofDays(7)));         // SPEC-PLAT-04 R61
    }

    @Scheduled(cron = "${gc.jobs.default-check-cron:0 5 * * * *}")
    public void checkDefaultPartitions() {
        run("default-partition-check", () -> {
            long rows = maintenance.defaultPartitionRows();
            defaultRows.set(rows);
            return rows;
        });
    }

    private void run(String job, java.util.function.LongSupplier work) {
        try {
            long result = work.getAsLong();
            log.debug("job {} finished: {}", job, result);
        } catch (RuntimeException e) {
            log.error("job {} failed; retrying on the next schedule", job, e);
        }
    }

}
