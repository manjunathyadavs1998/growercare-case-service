package com.growercare.casesvc.application;

import com.growercare.casesvc.application.port.AttachmentRepository;
import com.growercare.casesvc.application.port.CaseRepository;
import com.growercare.casesvc.application.port.ObjectStore;
import com.growercare.casesvc.application.port.ReferenceData;
import com.growercare.casesvc.domain.attachment.Attachment;
import com.growercare.outbox.OutboxWriter;
import com.growercare.shard.ShardId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SPEC-CASE-05 D4, R103–R107, R113, R114. Per shard, up to {@link #BATCH} PENDING attachments are each claimed and
 * decided in their own transaction ({@code FOR UPDATE SKIP LOCKED}, so replicas share the work and each file is
 * decided once). Each result (clean or quarantine object, status, activity, outbox) commits on its own, and the
 * staging object is deleted after commit.
 * A failing shard is logged and skipped (SPEC-CASE-04 R95).
 */
@Service
public class AttachmentScanJob {

    private static final Logger log = LoggerFactory.getLogger(AttachmentScanJob.class);
    static final int BATCH = 10;
    /** R114: failed attempts before an attachment is REJECTED. */
    static final int MAX_ATTEMPTS = 3;
    static final long SYSTEM_ACTOR = 0L;

    private final ReferenceData ref;
    private final AttachmentRepository repo;
    private final CaseRepository cases;
    private final ObjectStore store;
    private final ScanPipeline pipeline;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AttachmentScanJob(ReferenceData ref, AttachmentRepository repo, CaseRepository cases, ObjectStore store,
                             ScanPipeline pipeline, OutboxWriter outbox, TransactionTemplate tx, Clock clock) {
        this.ref = ref;
        this.repo = repo;
        this.cases = cases;
        this.store = store;
        this.pipeline = pipeline;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    /** @return attachments decided (CLEAN, INFECTED or REJECTED) across all shards */
    public int runOnce() {
        return (int) PerShard.sum("attachment-scan", ref.allShards(), this::scanShard);
    }

    /** R113: expires uploads never completed within {@code maxAge}; @return rows expired */
    public int expireStale(Duration maxAge) {
        var cutoff = clock.instant().minus(maxAge);
        return (int) PerShard.sum("attachment-expiry", ref.allShards(), shard -> {
            List<Attachment> expired = tx.execute(status -> repo.expireStale(shard, cutoff));
            expired = expired == null ? List.of() : expired;
            expired.forEach(a -> store.delete(ObjectStore.Area.STAGING, a.objectKey()));      // after commit
            return expired.size();
        });
    }

    /**
     * R114: each attachment is claimed and decided in <b>its own</b> transaction, so one broken file rolls back only
     * itself. A failed attachment is excluded for the rest of this run and its attempts are counted; at
     * {@link #MAX_ATTEMPTS} it is REJECTED, so it can never block the shard's queue.
     */
    long scanShard(ShardId shard) {
        var failedThisRun = new HashSet<Long>();
        int decided = 0;
        for (int i = 0; i < BATCH; i++) {
            var claimed = new Attachment[1];
            var temporary = new ArrayList<Path>();
            try {
                Boolean done = tx.execute(status -> {
                    var next = repo.claimNext(shard, failedThisRun);
                    if (next.isEmpty()) {
                        return null;                                                           // queue empty for this run
                    }
                    claimed[0] = next.get();
                    return decideAndRecord(shard, claimed[0], temporary);
                });
                if (done == null) {
                    break;
                }
                if (done) {
                    store.delete(ObjectStore.Area.STAGING, claimed[0].objectKey());           // only after commit
                    decided++;
                }
            } catch (RuntimeException e) {
                if (claimed[0] == null) {
                    throw e;                                                                   // claiming itself failed: shard-level (R95)
                }
                failedThisRun.add(claimed[0].attachmentId());
                decided += recordFailure(shard, claimed[0], e);
            } finally {
                temporary.forEach(AttachmentScanJob::deleteQuietly);
            }
        }
        return decided;
    }

    /** @return true when this run decided the attachment; false when another replica already had */
    private boolean decideAndRecord(ShardId shard, Attachment attachment, List<Path> temporary) {
        var outcome = decide(attachment, temporary);
        var result = Attachment.Status.valueOf(outcome.result().name());
        if (!repo.markScanned(shard, attachment.caseId(), attachment.attachmentId(), result, clock.instant())) {
            return false;                                                                      // decided elsewhere (R107)
        }
        record(shard, attachment, outcome);
        return true;
    }

    /** R114: count the failure; the third one rejects the attachment. @return 1 if it was rejected now, else 0 */
    private int recordFailure(ShardId shard, Attachment attachment, RuntimeException cause) {
        log.error("attachment {} of case {} could not be scanned ({}); skipped for this run",
                attachment.attachmentId(), attachment.caseId(), cause.getClass().getSimpleName(), cause);
        Integer rejected = tx.execute(status -> {
            int attempts = repo.recordScanFailure(shard, attachment.caseId(), attachment.attachmentId()).orElse(0);
            if (attempts < MAX_ATTEMPTS
                    || !repo.markScanned(shard, attachment.caseId(), attachment.attachmentId(), Attachment.Status.REJECTED, clock.instant())) {
                return 0;
            }
            record(shard, attachment, new ScanPipeline.Outcome(ScanPipeline.Result.REJECTED, null, "scan failed " + attempts + " times"));
            return 1;
        });
        return rejected == null ? 0 : rejected;
    }

    private ScanPipeline.Outcome decide(Attachment attachment, List<Path> temporary) {
        var file = store.download(ObjectStore.Area.STAGING, attachment.objectKey());
        temporary.add(file);
        var outcome = pipeline.inspect(file, attachment.contentType(), attachment.sha256Hex());
        if (outcome.result() == ScanPipeline.Result.CLEAN) {
            temporary.add(outcome.cleanFile());
            store.upload(ObjectStore.Area.CLEAN, attachment.objectKey(), outcome.cleanFile(), attachment.contentType());
        } else {
            store.upload(ObjectStore.Area.QUARANTINE, attachment.objectKey(), file, "application/octet-stream");
            log.error("attachment {} of case {} {}: {}", attachment.attachmentId(), attachment.caseId(),
                    outcome.result(), outcome.reason());                                         // ids only, no content
        }
        return outcome;
    }

    private void record(ShardId shard, Attachment attachment, ScanPipeline.Outcome outcome) {
        if (outcome.result() == ScanPipeline.Result.CLEAN) {
            long activity = cases.insertActivity(shard, attachment.caseId(), "ATTACHMENT_ADDED",
                    attachment.uploadedByUserId(), null, null, null);
            cases.insertActivityDetail(shard, attachment.caseId(), activity, "attachment_id", null,
                    Long.toString(attachment.attachmentId()));
        }
        outbox.append(CaseCommands.outboxOf(shard), new OutboxWriter.Event("case", Long.toString(attachment.caseId()),
                "case.attachment.scanned", Map.of(
                        "caseId", Long.toString(attachment.caseId()),
                        "attachmentId", Long.toString(attachment.attachmentId()),
                        "kind", attachment.kind(),
                        "result", outcome.result().name())), null);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("could not delete temporary file {}", file.getFileName());
        }
    }
}
