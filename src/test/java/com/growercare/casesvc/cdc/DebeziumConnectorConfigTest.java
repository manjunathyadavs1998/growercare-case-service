package com.growercare.casesvc.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SPEC-PLAT-04 R50, R52, R62, D8: the Debezium connector configs in {@code local-dev/debezium/} (registered by
 * {@code register.sh}). Debezium itself runs in the smoke test; this keeps the configs honest on every build.
 */
class DebeziumConnectorConfigTest {

    private static final Path DIR = Path.of("..", "..", "local-dev", "debezium").normalize();

    static JsonNode config(String file) throws IOException {
        return JsonMapper.builder().build().readTree(Files.readString(DIR.resolve(file)));
    }

    /** file, config key, expected value. */
    static Stream<Arguments> settings() {
        return Stream.of("case", "iam").flatMap(svc -> Stream.of(
                argumentSet("PLAT-D8 " + svc + ": pgoutput", svc + "-outbox.json", "plugin.name", "pgoutput"),
                argumentSet("PLAT-D8 " + svc + ": dedicated CDC role", svc + "-outbox.json", "database.user", "gc_cdc"),
                argumentSet("PLAT-D8 " + svc + ": password from the environment", svc + "-outbox.json", "database.password", "${env:GC_CDC_PASSWORD}"),
                argumentSet("PLAT-R52 " + svc + ": migration-owned publication", svc + "-outbox.json", "publication.name", "gc_outbox"),
                argumentSet("PLAT-R52 " + svc + ": never auto-create (FOR ALL TABLES)", svc + "-outbox.json", "publication.autocreate.mode", "disabled"),
                argumentSet("PLAT-R50 " + svc + ": outbox event router", svc + "-outbox.json", "transforms.outbox.type", "io.debezium.transforms.outbox.EventRouter"),
                argumentSet("PLAT-R50 " + svc + ": key = aggregate id", svc + "-outbox.json", "transforms.outbox.table.field.event.key", "aggregate_id"),
                argumentSet("PLAT-R50 " + svc + ": topic per aggregate", svc + "-outbox.json", "transforms.outbox.route.by.field", "aggregate_type"),
                argumentSet("PLAT-R50 " + svc + ": topic name", svc + "-outbox.json", "transforms.outbox.route.topic.replacement", svc + ".${routedByValue}.events"),
                argumentSet("PLAT-R50 " + svc + ": headers", svc + "-outbox.json", "transforms.outbox.table.fields.additional.placement",
                        "event_type:header:eventType,trace_id:header:traceId,occurred_at:header:occurredAt"),
                argumentSet("PLAT-R50 " + svc + ": string keys", svc + "-outbox.json", "key.converter", "org.apache.kafka.connect.storage.StringConverter"),
                argumentSet("PLAT-R50 " + svc + ": plain JSON values", svc + "-outbox.json", "value.converter.schemas.enable", "false")));
    }

    @ParameterizedTest
    @MethodSource("settings")
    void connectorSetting(String file, String key, String expected) throws IOException {
        assertThat(config(file).path("config").path(key).asString()).isEqualTo(expected);
    }

    /** file, table, captured by the event router. */
    static Stream<Arguments> tables() {
        return Stream.of(
                argumentSet("PLAT-R52 case: shard outbox captured", "case-outbox.json", "in_s01.outbox", true),
                argumentSet("PLAT-R52 case: BR shard outbox captured", "case-outbox.json", "br_s63.outbox", true),
                argumentSet("PLAT-R52 case: cases not captured", "case-outbox.json", "in_s01.cases", false),
                argumentSet("PLAT-R62 case: heartbeat not routed", "case-outbox.json", "msg.cdc_heartbeat", false),
                argumentSet("PLAT-R52 iam: outbox captured", "iam-outbox.json", "iam.outbox", true),
                argumentSet("PLAT-R52 iam: users not captured", "iam-outbox.json", "iam.user_account", false),
                argumentSet("PLAT-R62 iam: heartbeat not routed", "iam-outbox.json", "iam.cdc_heartbeat", false));
    }

    @ParameterizedTest
    @MethodSource("tables")
    void tableIncludeList(String file, String table, boolean captured) throws IOException {
        var include = Pattern.compile(config(file).path("config").path("table.include.list").asString());
        assertThat(include.matcher(table).matches()).isEqualTo(captured);
    }

    @ParameterizedTest(name = "PLAT-R62 {0}: heartbeat upsert keeps the slot moving")
    @MethodSource("files")
    void heartbeat(String file) throws IOException {
        var config = config(file).path("config");
        assertThat(config.path("heartbeat.interval.ms").asString()).isEqualTo("10000");
        assertThat(config.path("heartbeat.action.query").asString()).contains("cdc_heartbeat").contains("ON CONFLICT");
    }

    static Stream<String> files() {
        return Stream.of("case-outbox.json", "iam-outbox.json");
    }
}
