package com.growercare.casesvc.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.casesvc.application.MaintenanceJobs;
import com.growercare.casesvc.support.CaseApiTestBase;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/** SPEC-PLAT-04 R55 / E64 (outbox, 3 days) and R61 / E70 (inbox, 7 days). */
class EventHousekeepingIT extends CaseApiTestBase {

    enum Store { OUTBOX, INBOX }

    @Autowired
    MaintenanceJobs maintenance;

    /** store, row age in days, rows left after the job. */
    static Stream<Arguments> rows() {
        return Stream.of(
                argumentSet("PLAT-E64a outbox row 4 days old is deleted", Store.OUTBOX, 4, 0),
                argumentSet("PLAT-E64b outbox row 1 day old is kept", Store.OUTBOX, 1, 1),
                argumentSet("PLAT-E70a inbox row 8 days old is deleted", Store.INBOX, 8, 0),
                argumentSet("PLAT-E70b inbox row 1 day old is kept", Store.INBOX, 1, 1));
    }

    @ParameterizedTest
    @MethodSource("rows")
    void expiredRowsAreDeleted(Store store, int ageDays, int expectedRows) {
        var id = UUID.randomUUID();
        if (store == Store.OUTBOX) {
            owner.sql("""
                    INSERT INTO in_s01.outbox (event_id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
                    VALUES (?, 'case', '1', 'test.housekeeping', '{}', now() - make_interval(days => ?))""")   // not a contract type (CaseEventContractIT)
                    .params(id, ageDays).update();
            maintenance.cleanupOutbox(Duration.ofDays(3));
            assertThat(owner.sql("SELECT count(*) FROM in_s01.outbox WHERE event_id = ?").param(id)
                    .query(Integer.class).single()).isEqualTo(expectedRows);
        } else {
            owner.sql("INSERT INTO msg.inbox (consumer_group, event_id, processed_at) VALUES ('test', ?, now() - make_interval(days => ?))")
                    .params(id, ageDays).update();
            maintenance.cleanupInbox(Duration.ofDays(7));
            assertThat(owner.sql("SELECT count(*) FROM msg.inbox WHERE event_id = ?").param(id)
                    .query(Integer.class).single()).isEqualTo(expectedRows);
        }
    }
}
