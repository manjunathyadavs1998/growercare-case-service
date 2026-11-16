package com.growercare.casesvc.api;

import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.casesvc.support.DevSeedPrincipals.TM_IN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.application.SlaTimerPoller;
import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.casesvc.support.CaseTestDatabase;
import com.growercare.shard.ShardId;
import com.growercare.testsupport.EventContracts;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * SPEC-PLAT-04 R54 / E63: every event type case-service emits is produced by the real use case (over HTTP and the
 * SLA poller) and every outbox row of that type validates against {@code contracts/events/<type>.schema.json}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaseEventContractIT extends CaseApiTestBase {

    @Autowired
    SlaTimerPoller poller;

    /** One case through its life: create → assign → transition → share/unshare → comment → SLA timers fire. */
    @BeforeAll
    void produceEveryEventType() throws Exception {
        var caseId = createCase(GROWER_IN1, complaint());                                      // created, routed
        as(TM_IN, put("/cases/{id}/assignee", caseId).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeUserId\":\"102\"}"))
                .andExpect(status().isOk());                                                    // assigned
        as(CARE_IN, post("/cases/{id}/transitions", caseId).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"toStatus\":\"TRIAGED\"}"))
                .andExpect(status().isOk());                                                    // status_changed
        as(GROWER_IN1, put("/cases/{id}/shares/{p}", caseId, "502")).andExpect(status().isOk());               // shared
        as(GROWER_IN1, delete("/cases/{id}/shares/{p}", caseId, "502")).andExpect(status().isNoContent());     // unshared
        as(GROWER_IN1, post("/cases/{id}/comments", caseId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientMsgId\":\"" + UUID.randomUUID() + "\",\"visibility\":\"PUBLIC\",\"body\":\"Any update?\"}"))
                .andExpect(status().isCreated());                                               // comment.posted
        owner.sql("UPDATE %s.case_timer SET due_at = now() - interval '1 minute' WHERE case_id = ?"
                .formatted(shardOf(caseId).schemaName())).param(CaseSlaRoutingIT.numericId(caseId)).update();
        poller.runOnce();                                                                       // sla.warning ×3, sla.breached
    }

    static Stream<Arguments> eventTypes() {
        return Stream.of("case.created", "case.routed", "case.assigned", "case.status_changed", "case.shared",
                        "case.unshared", "comment.posted", "case.sla.warning", "case.sla.breached")
                .map(type -> argumentSet("PLAT-E63 " + type, type));
    }

    @ParameterizedTest
    @MethodSource("eventTypes")
    void everyOutboxRowMatchesItsContract(String eventType) {
        var envelopes = owner.sql(CaseTestDatabase.TEST_SHARDS.stream().map(ShardId::schemaName)
                        .map(schema -> "SELECT payload::text FROM " + schema + ".outbox WHERE event_type = :type")
                        .collect(Collectors.joining(" UNION ALL ")))
                .param("type", eventType).query(String.class).list();

        assertThat(envelopes).as("events of type %s produced by the scenario", eventType).isNotEmpty();
        List<String> problems = envelopes.stream().flatMap(e -> EventContracts.violations(eventType, e).stream()).distinct().toList();
        assertThat(problems).isEmpty();
    }
}
