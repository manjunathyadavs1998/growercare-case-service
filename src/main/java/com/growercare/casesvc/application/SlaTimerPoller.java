package com.growercare.casesvc.application;

import com.growercare.casesvc.application.port.CaseRepository;
import com.growercare.casesvc.application.port.ReferenceData;
import com.growercare.casesvc.application.port.SlaTimerRepository;
import com.growercare.casesvc.domain.SlaRules.TimerKind;
import com.growercare.outbox.OutboxWriter;
import com.growercare.shard.ShardId;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SPEC-CASE-04 R82/R83: fires due SLA timers. Per shard, one transaction claims a batch with
 * {@code FOR UPDATE SKIP LOCKED}, marks each timer fired and writes activity + outbox event, so several replicas
 * can poll concurrently and every timer is recorded exactly once. Runs without a user principal.
 */
@Service
public class SlaTimerPoller {

    private static final Logger log = LoggerFactory.getLogger(SlaTimerPoller.class);
    static final int BATCH = 200;
    static final long SYSTEM_ACTOR = 0L;     // activity actor for system-generated entries

    private final ReferenceData ref;
    private final SlaTimerRepository timers;
    private final CaseRepository cases;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    public SlaTimerPoller(ReferenceData ref, SlaTimerRepository timers, CaseRepository cases, OutboxWriter outbox, TransactionTemplate tx) {
        this.ref = ref;
        this.timers = timers;
        this.cases = cases;
        this.outbox = outbox;
        this.tx = tx;
    }

    /** @return number of timers fired across all shards */
    public int runOnce() {
        int fired = ref.allShards().stream().mapToInt(this::pollShard).sum();
        if (fired > 0) {
            log.info("SLA poller fired {} timer(s)", fired);
        }
        return fired;
    }

    int pollShard(ShardId shard) {
        Integer fired = tx.execute(status -> {
            int count = 0;
            for (var timer : timers.claimDueTimers(shard, BATCH)) {
                if (!timers.markFired(shard, timer.caseId(), timer.timerId())) {
                    continue;                       // already fired/cancelled: exactly-once guard
                }
                boolean breach = timer.kind() == TimerKind.BREACH;
                long activity = cases.insertActivity(shard, timer.caseId(), breach ? "SLA_BREACHED" : "SLA_WARNING",
                        SYSTEM_ACTOR, null, null, null);
                cases.insertActivityDetail(shard, timer.caseId(), activity, "threshold", null,
                        Integer.toString(timer.kind().percent()));
                outbox.append(CaseCommands.outboxOf(shard), new OutboxWriter.Event("case", Long.toString(timer.caseId()),
                        breach ? "case.sla.breached" : "case.sla.warning",
                        Map.of("caseId", Long.toString(timer.caseId()), "threshold", timer.kind().percent(),
                                "slaDueAt", timer.slaDueAt().toString())), null);
                count++;
            }
            return count;
        });
        return fired == null ? 0 : fired;
    }
}
