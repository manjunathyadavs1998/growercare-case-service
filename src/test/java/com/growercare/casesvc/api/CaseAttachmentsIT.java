package com.growercare.casesvc.api;

import static com.growercare.casesvc.support.DevSeedPrincipals.AUDITOR_TEST;
import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_BR;
import static com.growercare.casesvc.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.casesvc.support.DevSeedPrincipals.GROWER_IN2;
import static com.growercare.casesvc.support.DevSeedPrincipals.RETAILER_IN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.casesvc.application.AttachmentScanJob;
import com.growercare.casesvc.support.CaseApiTestBase;
import com.growercare.casesvc.support.TestImages;
import com.growercare.security.GcPrincipal;
import com.growercare.testsupport.S3TestContainer;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * SPEC-CASE-05 over HTTP with a real S3 API (SeaweedFS), Postgres and the scan job. Rows are named after the spec
 * example IDs; every row creates its own case, so rows are independent.
 */
class CaseAttachmentsIT extends CaseApiTestBase {

    static final String STAGING = "gc-media-staging";
    static final String CLEAN = "gc-media-clean";
    static final String QUARANTINE = "gc-media-quarantine";
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final S3Client S3 = S3TestContainer.client();

    @Autowired
    AttachmentScanJob scanJob;

    // ============================================================================================ helpers

    /** Case "A": raised by retailer.in (partner 501) for grower.in1, so retailer, grower and staff all see it. */
    String caseA() throws Exception {
        return createCase(RETAILER_IN, complaint());
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    ResultActions request(GcPrincipal actor, String caseId, String kind, String contentType, long size, String sha) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("kind", kind);
        body.put("contentType", contentType);
        body.put("sizeBytes", size);
        body.put("sha256", sha);
        return as(actor, post("/cases/{id}/attachments", caseId).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    /** The upload response: attachment id, presigned URL and the headers to send. */
    record Upload(String attachmentId, String url, Map<String, String> headers) { }

    Upload requestOk(GcPrincipal actor, String caseId, String kind, String contentType, byte[] bytes) throws Exception {
        var json = request(actor, caseId, kind, contentType, bytes.length, sha256(bytes))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Map<String, String> headers = JsonPath.read(json, "$.upload.headers");
        return new Upload(JsonPath.read(json, "$.attachmentId"), JsonPath.read(json, "$.upload.url"), headers);
    }

    int put(Upload upload, byte[] bytes) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(upload.url())).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        upload.headers().forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    ResultActions complete(GcPrincipal actor, String caseId, String attachmentId) throws Exception {
        return as(actor, post("/cases/{id}/attachments/{a}/complete", caseId, attachmentId));
    }

    /** request → PUT → complete; returns the attachment id (status PENDING). */
    String uploaded(GcPrincipal actor, String caseId, String kind, String contentType, byte[] bytes) throws Exception {
        var upload = requestOk(actor, caseId, kind, contentType, bytes);
        assertThat(put(upload, bytes)).isEqualTo(200);
        complete(actor, caseId, upload.attachmentId()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        return upload.attachmentId();
    }

    String scanned(GcPrincipal actor, String caseId, String kind, String contentType, byte[] bytes) throws Exception {
        var id = uploaded(actor, caseId, kind, contentType, bytes);
        scanJob.runOnce();
        return id;
    }

    String statusOf(String caseId, String attachmentId) {
        return owner.sql("SELECT scan_status FROM %s.case_attachment WHERE case_id = ? AND attachment_id = ?"
                        .formatted(shardOf(caseId).schemaName()))
                .params(CaseSlaRoutingIT.numericId(caseId), CaseSlaRoutingIT.numericId(attachmentId)).query(String.class).single();
    }

    String keyOf(String caseId, String attachmentId) {
        return shardOf(caseId).schemaName() + "/" + caseId + "/" + attachmentId;
    }

    static byte[] object(String bucket, String key) {
        try {
            return S3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    int events(String caseId, String attachmentId, String result) {
        return owner.sql("""
                        SELECT count(*) FROM %s.outbox WHERE event_type = 'case.attachment.scanned'
                           AND payload -> 'data' ->> 'attachmentId' = ? AND payload -> 'data' ->> 'result' = ?"""
                        .formatted(shardOf(caseId).schemaName()))
                .params(attachmentId, result).query(Integer.class).single();
    }

    // ============================================================================================ R96–R99

    @Nested
    class Requesting {

        static Stream<Arguments> uploaders() {
            return Stream.of(
                    argumentSet("CASE-E103a grower (owner)", GROWER_IN1),
                    argumentSet("CASE-E103b care agent", CARE_IN),
                    argumentSet("CASE-E103c retailer (raised via 501)", RETAILER_IN));
        }

        @ParameterizedTest
        @MethodSource("uploaders")
        void uploadUrlIsIssued(GcPrincipal actor) throws Exception {
            var caseId = caseA();
            var json = request(actor, caseId, "PHOTO", "image/jpeg", 2_000_000, sha256(new byte[] {1}))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("AWAITING_UPLOAD"))
                    .andExpect(jsonPath("$.upload.method").value("PUT"))
                    .andReturn().getResponse().getContentAsString();
            Instant expires = Instant.parse(JsonPath.read(json, "$.upload.expiresAt"));
            assertThat(expires).isBetween(Instant.now().plusSeconds(240), Instant.now().plusSeconds(310));
            String url = JsonPath.read(json, "$.upload.url");
            assertThat(url).doesNotContain("70001").doesNotContain("Patil");                     // ids only (PRV-5)
        }

        /** kind, content type, size, sha, field. */
        static Stream<Arguments> invalid() {
            return Stream.of(
                    argumentSet("CASE-E104a unknown kind", "SELFIE", "image/jpeg", 1000L, "a".repeat(64), "kind"),
                    argumentSet("CASE-E104b photo as pdf", "PHOTO", "application/pdf", 1000L, "a".repeat(64), "contentType"),
                    argumentSet("CASE-E104c photo 26 MB", "PHOTO", "image/jpeg", 26L * 1024 * 1024, "a".repeat(64), "sizeBytes"),
                    argumentSet("CASE-E104d video 101 MB", "VIDEO", "video/mp4", 101L * 1024 * 1024, "a".repeat(64), "sizeBytes"),
                    argumentSet("CASE-E104e sha256 not hex", "PHOTO", "image/jpeg", 1000L, "xyz", "sha256"));
        }

        @ParameterizedTest
        @MethodSource("invalid")
        void invalidRequestsAre400(String kind, String contentType, long size, String sha, String field) throws Exception {
            problem(request(GROWER_IN1, caseA(), kind, contentType, size, sha), 400, "VALIDATION_FAILED")
                    .andExpect(jsonPath("$.errors[0].field").value(field));
        }

        static Stream<Arguments> denied() {
            return Stream.of(
                    argumentSet("CASE-E105a no case:attach", AUDITOR_TEST, 403, "PERMISSION_DENIED"),
                    argumentSet("CASE-E105b other country", CARE_BR, 404, "NOT_FOUND"));
        }

        @ParameterizedTest
        @MethodSource("denied")
        void deniedRequests(GcPrincipal actor, int status, String code) throws Exception {
            problem(request(actor, caseA(), "PHOTO", "image/jpeg", 1000, "a".repeat(64)), status, code);
        }

        @Test
        void caseE106aTwentyFirstLiveAttachmentIsRefused() throws Exception {
            var caseId = caseA();
            for (int i = 0; i < 20; i++) {
                request(GROWER_IN1, caseId, "PHOTO", "image/jpeg", 1000, "a".repeat(64)).andExpect(status().isCreated());
            }
            problem(request(GROWER_IN1, caseId, "PHOTO", "image/jpeg", 1000, "a".repeat(64)), 422, "BUSINESS_RULE_VIOLATION")
                    .andExpect(jsonPath("$.rule").value("attachment.limit-reached"));
        }

        @Test
        void caseE106cConcurrentRequestsCannotExceedTheLimit() throws Exception {
            var caseId = caseA();
            Callable<Integer> one = () -> request(GROWER_IN1, caseId, "PHOTO", "image/jpeg", 1000, "a".repeat(64))
                    .andReturn().getResponse().getStatus();
            var statuses = new java.util.ArrayList<Integer>();
            try (var pool = Executors.newFixedThreadPool(8)) {
                for (var future : pool.invokeAll(java.util.Collections.nCopies(25, one))) {
                    statuses.add(future.get());
                }
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(20);
            assertThat(statuses).filteredOn(s -> s == 422).hasSize(5);
            assertThat(count("SELECT count(*) FROM {shard}.case_attachment WHERE case_id = ?", caseId)).isEqualTo(20);
        }

        /** actor, expected status (closed case). */
        static Stream<Arguments> closedCase() {
            return Stream.of(
                    argumentSet("CASE-E106b grower on a closed case", GROWER_IN1, 422),
                    argumentSet("CASE-E107 staff on a closed case", CARE_IN, 201));
        }

        @ParameterizedTest
        @MethodSource("closedCase")
        void closedCases(GcPrincipal actor, int status) throws Exception {
            var caseId = caseA();
            forceStatus(caseId, "CLOSED", true);
            var result = request(actor, caseId, "PHOTO", "image/jpeg", 1000, "a".repeat(64)).andExpect(status().is(status));
            if (status == 422) {
                result.andExpect(jsonPath("$.rule").value("attachment.case-closed"));
            }
        }
    }

    // ============================================================================================ R100–R102

    @Nested
    class Completing {

        @Test
        void caseE108CompleteIsIdempotent() throws Exception {
            var caseId = caseA();
            var bytes = TestImages.jpegWithGps();
            var upload = requestOk(GROWER_IN1, caseId, "PHOTO", "image/jpeg", bytes);
            assertThat(put(upload, bytes)).isEqualTo(200);
            complete(GROWER_IN1, caseId, upload.attachmentId()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
            complete(GROWER_IN1, caseId, upload.attachmentId()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        void caseE109aNothingUploaded() throws Exception {
            var caseId = caseA();
            var upload = requestOk(GROWER_IN1, caseId, "PHOTO", "image/jpeg", TestImages.jpegWithGps());
            problem(complete(GROWER_IN1, caseId, upload.attachmentId()), 422, "BUSINESS_RULE_VIOLATION")
                    .andExpect(jsonPath("$.rule").value("attachment.upload-missing"));
            assertThat(statusOf(caseId, upload.attachmentId())).isEqualTo("AWAITING_UPLOAD");
        }

        @Test
        void caseE109bObjectOfAnotherSize() throws Exception {
            var caseId = caseA();
            var upload = requestOk(GROWER_IN1, caseId, "PHOTO", "image/jpeg", TestImages.jpegWithGps());
            S3.putObject(b -> b.bucket(STAGING).key(keyOf(caseId, upload.attachmentId())), RequestBody.fromBytes(new byte[] {1, 2, 3}));
            problem(complete(GROWER_IN1, caseId, upload.attachmentId()), 422, "BUSINESS_RULE_VIOLATION")
                    .andExpect(jsonPath("$.rule").value("attachment.upload-mismatch"));
        }

        static Stream<Arguments> notTheUploader() {
            return Stream.of(
                    argumentSet("CASE-E110a another grower", GROWER_IN2),
                    argumentSet("CASE-E110b staff", CARE_IN));
        }

        @ParameterizedTest
        @MethodSource("notTheUploader")
        void onlyTheUploaderCompletes(GcPrincipal actor) throws Exception {
            var caseId = caseA();
            var bytes = TestImages.jpegWithGps();
            var upload = requestOk(GROWER_IN1, caseId, "PHOTO", "image/jpeg", bytes);
            put(upload, bytes);
            problem(complete(actor, caseId, upload.attachmentId()), 404, "NOT_FOUND");
        }
    }

    // ============================================================================================ R103–R107

    @Nested
    class Scanning {

        @Test
        void caseE111CleanJpegIsPublishedWithoutGps() throws Exception {
            var caseId = caseA();
            var id = scanned(GROWER_IN1, caseId, "PHOTO", "image/jpeg", TestImages.jpegWithGps());

            assertThat(statusOf(caseId, id)).isEqualTo("CLEAN");
            var published = object(CLEAN, keyOf(caseId, id));
            assertThat(published).isNotNull();
            assertThat(TestImages.contains(published, "GPSLatitude")).isFalse();
            assertThat(object(STAGING, keyOf(caseId, id))).isNull();
            assertThat(count("SELECT count(*) FROM {shard}.case_activity WHERE case_id = ? AND activity_type = 'ATTACHMENT_ADDED'", caseId)).isEqualTo(1);
            assertThat(events(caseId, id, "CLEAN")).isEqualTo(1);
        }

        @Test
        void caseE112CleanPngLosesTextChunks() throws Exception {
            var caseId = caseA();
            var id = scanned(GROWER_IN1, caseId, "PACK_PHOTO", "image/png", TestImages.pngWithText());
            assertThat(statusOf(caseId, id)).isEqualTo("CLEAN");
            assertThat(TestImages.contains(object(CLEAN, keyOf(caseId, id)), "tEXt")).isFalse();
        }

        /** declared kind/type, actual bytes, declared bytes (for the checksum), expected status. */
        static Stream<Arguments> unsafe() {
            var eicar = TestImages.EICAR.getBytes(StandardCharsets.US_ASCII);
            var pdf = TestImages.pdf();
            var tampered = pdf.clone();
            tampered[tampered.length - 2] = 'X';                                                 // same size, other SHA-256
            return Stream.of(
                    argumentSet("CASE-E113 eicar is INFECTED", "INVOICE", "application/pdf", eicar, eicar, "INFECTED"),
                    argumentSet("CASE-E114a pdf declared as png is REJECTED", "PHOTO", "image/png", pdf, pdf, "REJECTED"),
                    argumentSet("CASE-E114b other bytes of the same size are REJECTED", "INVOICE", "application/pdf", tampered, pdf, "REJECTED"));
        }

        @ParameterizedTest
        @MethodSource("unsafe")
        void unsafeFilesAreQuarantined(String kind, String type, byte[] actual, byte[] declared, String expected) throws Exception {
            var caseId = caseA();
            var upload = requestOk(GROWER_IN1, caseId, kind, type, declared);
            assertThat(put(upload, actual)).isEqualTo(200);
            complete(GROWER_IN1, caseId, upload.attachmentId()).andExpect(status().isOk());
            scanJob.runOnce();

            var key = keyOf(caseId, upload.attachmentId());
            assertThat(statusOf(caseId, upload.attachmentId())).isEqualTo(expected);
            assertThat(object(QUARANTINE, key)).isEqualTo(actual);
            assertThat(object(CLEAN, key)).isNull();
            assertThat(events(caseId, upload.attachmentId(), expected)).isEqualTo(1);
        }

        @Test
        void caseE122OneBrokenAttachmentDoesNotBlockTheShard() throws Exception {
            var caseId = caseA();
            var broken = uploaded(GROWER_IN1, caseId, "PHOTO", "image/jpeg", TestImages.jpegWithGps());
            var healthy = uploaded(GROWER_IN1, caseId, "INVOICE", "application/pdf", TestImages.pdf());
            S3.deleteObject(b -> b.bucket(STAGING).key(keyOf(caseId, broken)));                // processing it now throws

            scanJob.runOnce();
            assertThat(statusOf(caseId, healthy)).isEqualTo("CLEAN");                         // the shard moved on
            assertThat(statusOf(caseId, broken)).isEqualTo("PENDING");
            assertThat(scanAttempts(caseId, broken)).isEqualTo(1);

            scanJob.runOnce();
            scanJob.runOnce();
            assertThat(statusOf(caseId, broken)).isEqualTo("REJECTED");                       // 3 strikes
            assertThat(events(caseId, broken, "REJECTED")).isEqualTo(1);
        }

        int scanAttempts(String caseId, String attachmentId) {
            return owner.sql("SELECT scan_attempts FROM %s.case_attachment WHERE case_id = ? AND attachment_id = ?"
                            .formatted(shardOf(caseId).schemaName()))
                    .params(CaseSlaRoutingIT.numericId(caseId), CaseSlaRoutingIT.numericId(attachmentId)).query(Integer.class).single();
        }

        @Test
        void caseE115ConcurrentScansDecideOnce() throws Exception {
            var caseId = caseA();
            var id = uploaded(GROWER_IN1, caseId, "PHOTO", "image/jpeg", TestImages.jpegWithGps());
            try (var pool = Executors.newFixedThreadPool(2)) {
                pool.invokeAll(List.<Callable<Integer>>of(scanJob::runOnce, scanJob::runOnce));
            }
            assertThat(events(caseId, id, "CLEAN")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM {shard}.case_activity WHERE case_id = ? AND activity_type = 'ATTACHMENT_ADDED'", caseId)).isEqualTo(1);
        }
    }

    // ============================================================================================ R108–R112

    @Nested
    class VisibilityAndComments {

        record Fixture(String caseId, String cleanPhoto, String pendingByGrower, String internalAttachment) { }

        /** A CLEAN case photo, a PENDING upload by grower.in1, and care.in's CLEAN file linked to an INTERNAL comment. */
        Fixture fixture() throws Exception {
            var caseId = caseA();
            var clean = scanned(GROWER_IN1, caseId, "PHOTO", "image/jpeg", TestImages.jpegWithGps());
            var internal = scanned(CARE_IN, caseId, "LAB_REPORT", "application/pdf", TestImages.pdf());
            comment(CARE_IN, caseId, "INTERNAL", List.of(internal)).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.attachmentIds[0]").value(internal));
            var pending = uploaded(GROWER_IN1, caseId, "INVOICE", "application/pdf", TestImages.pdf());
            return new Fixture(caseId, clean, pending, internal);
        }

        ResultActions comment(GcPrincipal actor, String caseId, String visibility, List<String> attachmentIds) throws Exception {
            var body = new LinkedHashMap<String, Object>();
            body.put("clientMsgId", UUID.randomUUID().toString());
            body.put("visibility", visibility);
            body.put("body", "See attached");
            body.put("attachmentIds", attachmentIds);
            return as(actor, post("/cases/{id}/comments", caseId).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
        }

        /** actor → which fixture attachments they list. */
        static Stream<Arguments> listing() {
            return Stream.of(
                    argumentSet("CASE-E116a grower: clean + own pending", GROWER_IN1, Set.of("clean", "pending")),
                    argumentSet("CASE-E116b care (staff): all", CARE_IN, Set.of("clean", "pending", "internal")),
                    argumentSet("CASE-E116c retailer: clean only", RETAILER_IN, Set.of("clean")),
                    argumentSet("CASE-E116d another grower: 404", GROWER_IN2, null));
        }

        @ParameterizedTest
        @MethodSource("listing")
        void listIsFilteredPerCaller(GcPrincipal actor, Set<String> expected) throws Exception {
            var f = fixture();
            var result = as(actor, get("/cases/{id}/attachments", f.caseId()));
            if (expected == null) {
                problem(result, 404, "NOT_FOUND");
                return;
            }
            List<String> ids = JsonPath.read(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.items[*].attachmentId");
            var names = Map.of(f.cleanPhoto(), "clean", f.pendingByGrower(), "pending", f.internalAttachment(), "internal");
            assertThat(ids.stream().map(names::get).toList()).containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        void caseE117PartnerDownloadsTheStrippedCleanFile() throws Exception {
            var f = fixture();
            var json = as(RETAILER_IN, get("/cases/{id}/attachments/{a}/download", f.caseId(), f.cleanPhoto()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(Instant.parse(JsonPath.read(json, "$.expiresAt"))).isBefore(Instant.now().plusSeconds(70));
            var response = HTTP.send(HttpRequest.newBuilder(URI.create(JsonPath.read(json, "$.url"))).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(object(CLEAN, keyOf(f.caseId(), f.cleanPhoto())));
            assertThat(TestImages.contains(response.body(), "GPSLatitude")).isFalse();
        }

        static Stream<Arguments> notDownloadable() {
            return Stream.of(
                    argumentSet("CASE-E118a own file still pending", GROWER_IN1, "pending", 409, "NOT_READY"),
                    argumentSet("CASE-E118b INTERNAL-comment file for a partner", RETAILER_IN, "internal", 404, "NOT_FOUND"));
        }

        @ParameterizedTest
        @MethodSource("notDownloadable")
        void notDownloadable(GcPrincipal actor, String which, int status, String code) throws Exception {
            var f = fixture();
            var id = "pending".equals(which) ? f.pendingByGrower() : f.internalAttachment();
            var result = problem(as(actor, get("/cases/{id}/attachments/{a}/download", f.caseId(), id)), status, code);
            if (status == 409) {
                result.andExpect(jsonPath("$.rule").value("attachment.not-available"));
            }
        }

        @Test
        void caseE119LinkedAttachmentInheritsCommentVisibility() throws Exception {
            var caseId = caseA();
            var photo = scanned(CARE_IN, caseId, "PHOTO", "image/jpeg", TestImages.jpegWithGps());
            as(GROWER_IN1, get("/cases/{id}/attachments/{a}/download", caseId, photo)).andExpect(status().isOk());   // case-level: visible
            comment(CARE_IN, caseId, "INTERNAL", List.of(photo)).andExpect(status().isCreated());
            problem(as(GROWER_IN1, get("/cases/{id}/attachments/{a}/download", caseId, photo)), 404, "NOT_FOUND");   // now INTERNAL
            assertThat(owner.sql("SELECT count(*) FROM %s.outbox WHERE event_type = 'comment.posted' AND payload -> 'data' -> 'attachmentIds' @> to_jsonb(?::text)"
                    .formatted(shardOf(caseId).schemaName())).param(photo).query(Integer.class).single()).isEqualTo(1);
        }

        /** which attachment ids grower.in1 tries to link. */
        static Stream<Arguments> invalidLinks() {
            return Stream.of(
                    argumentSet("CASE-E120a someone else's file", "care"),
                    argumentSet("CASE-E120b already linked", "linked"),
                    argumentSet("CASE-E120c infected file", "infected"),
                    argumentSet("CASE-E120d eleven ids", "eleven"));
        }

        @ParameterizedTest
        @MethodSource("invalidLinks")
        void invalidLinksRejectTheWholeComment(String which) throws Exception {
            var caseId = caseA();
            List<String> ids = switch (which) {
                case "care" -> List.of(scanned(CARE_IN, caseId, "PHOTO", "image/jpeg", TestImages.jpegWithGps()));
                case "linked" -> {
                    var own = scanned(GROWER_IN1, caseId, "PHOTO", "image/jpeg", TestImages.jpegWithGps());
                    comment(GROWER_IN1, caseId, "PUBLIC", List.of(own)).andExpect(status().isCreated());
                    yield List.of(own);
                }
                case "infected" -> List.of(scanned(GROWER_IN1, caseId, "INVOICE", "application/pdf",
                        TestImages.EICAR.getBytes(StandardCharsets.US_ASCII)));
                default -> Stream.generate(() -> "1").limit(11).toList();
            };
            int before = count("SELECT count(*) FROM {shard}.case_comment WHERE case_id = ?", caseId);
            problem(comment(GROWER_IN1, caseId, "PUBLIC", ids), 422, "BUSINESS_RULE_VIOLATION")
                    .andExpect(jsonPath("$.rule").value("comment.attachment-invalid"));
            assertThat(count("SELECT count(*) FROM {shard}.case_comment WHERE case_id = ?", caseId)).isEqualTo(before);
        }
    }

    // ============================================================================================ R113

    @Nested
    class Expiry {

        /** age in hours, expected status, staging object kept. */
        static Stream<Arguments> ages() {
            return Stream.of(
                    argumentSet("CASE-E121a 25 h old upload expires", 25, "EXPIRED", false),
                    argumentSet("CASE-E121b 1 h old upload stays", 1, "AWAITING_UPLOAD", true));
        }

        @ParameterizedTest
        @MethodSource("ages")
        void staleUploadsExpire(int ageHours, String expected, boolean objectKept) throws Exception {
            var caseId = caseA();
            var bytes = TestImages.jpegWithGps();
            var upload = requestOk(GROWER_IN1, caseId, "PHOTO", "image/jpeg", bytes);
            put(upload, bytes);                                                                   // uploaded but never completed
            owner.sql("UPDATE %s.case_attachment SET created_at = now() - make_interval(hours => ?) WHERE case_id = ? AND attachment_id = ?"
                            .formatted(shardOf(caseId).schemaName()))
                    .params(ageHours, CaseSlaRoutingIT.numericId(caseId), CaseSlaRoutingIT.numericId(upload.attachmentId())).update();

            scanJob.expireStale(Duration.ofHours(24));

            assertThat(statusOf(caseId, upload.attachmentId())).isEqualTo(expected);
            assertThat(object(STAGING, keyOf(caseId, upload.attachmentId())) != null).isEqualTo(objectKept);
        }
    }
}
