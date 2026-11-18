package com.growercare.casesvc.adapters.in.web;

import com.growercare.casesvc.domain.CaseChildren.Activity;
import com.growercare.casesvc.application.CommentsAndSharing;
import com.growercare.casesvc.application.port.ObjectStore;
import com.growercare.casesvc.domain.attachment.Attachment;
import com.growercare.casesvc.domain.CaseChildren.Share;
import com.growercare.casesvc.domain.CaseRecord;
import com.growercare.casesvc.domain.CaseRules;
import com.growercare.casesvc.domain.SlaRules;
import com.growercare.common.error.NotFoundException;
import com.growercare.common.error.ValidationException;
import com.growercare.security.PiiClearance;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;

/** Public JSON contracts of case-service (SPEC-CASE-02/03 §3). IDs are strings. */
final class CaseDtos {

    private CaseDtos() { }

    static final String ID = "^[1-9][0-9]{0,18}$";
    static final String CODE = "^[A-Z][A-Z_]{1,39}$";
    private static final java.util.regex.Pattern ETAG = java.util.regex.Pattern.compile("^(?:W/)?\"(\\d{1,9})\"$");
    private static final java.util.regex.Pattern UUID_TEXT =
            java.util.regex.Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    record CreateCaseRequest(
            @NotBlank @Pattern(regexp = ID) String growerId,
            @NotBlank @Pattern(regexp = CODE) String caseType,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 4000) String description,
            @Pattern(regexp = ID) String farmId,
            @Pattern(regexp = ID) String productId,
            @Pattern(regexp = "^[A-Z0-9-]{4,32}$") String batchNo,
            @Pattern(regexp = "^[A-Z]{2,8}$") String buCode,
            @Pattern(regexp = "^(LOW|NORMAL|HIGH|URGENT)$") String priority) { }

    record TransitionRequest(@NotBlank @Pattern(regexp = CODE) String toStatus, @Size(max = 1000) String note) { }

    record AssignRequest(@NotBlank @Pattern(regexp = ID) String assigneeUserId) { }

    record PostCommentRequest(
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F-]{36}$") String clientMsgId,
            @NotBlank @Pattern(regexp = "^(PUBLIC|INTERNAL|PARTNER)$") String visibility,
            @NotBlank @Size(max = 4000) String body,
            @Pattern(regexp = ID) String replyToCommentId,
            List<@NotBlank @Pattern(regexp = ID) String> attachmentIds) { }   // SPEC-CASE-05 R111 (max 10 → 422, R112)

    record CaseView(
            String caseId, String caseNo, String caseType, String status, String priority,
            String countryCode, String territoryId, String buCode,
            String growerId, String growerName, String growerPhoneLast4, String farmId,
            String raisedByUserId, String raisedChannel, String raisedViaPartnerOrgId,
            String productId, String batchNo, String title, String description, String assigneeUserId,
            Instant slaDueAt, Instant createdAt, Instant updatedAt, Instant closedAt, int version,
            String queueCode, String slaState,
            List<TransitionView> allowedTransitions, Boolean canAssign, List<String> sharedWithPartnerOrgIds) {

        /** R30: the grower's name is masked unless the caller has FULL clearance; the phone never leaves. */
        static CaseView of(CaseRecord c, PiiClearance clearance, SlaRules.SlaState slaState) {
            return of(c, clearance, slaState, null, null);
        }

        /** SPEC-CASE-09: single-case responses carry what the caller may do next; lists leave both fields null (D3). */
        static CaseView of(CaseRecord c, PiiClearance clearance, SlaRules.SlaState slaState,
                           com.growercare.casesvc.application.CaseQueries.AllowedActions actions, List<String> sharedWith) {
            return new CaseView(
                    id(c.caseId()), c.caseNo().formatted(), c.caseType(), c.status(), c.priority(),
                    c.countryCode(), id(c.territoryId()), c.buCode(),
                    id(c.growerId()), clearance == PiiClearance.FULL ? c.growerName() : CaseRules.maskName(c.growerName()),
                    c.growerPhoneLast4(), id(c.farmId()),
                    id(c.raisedByUserId()), c.raisedChannel(), id(c.raisedViaPartnerOrgId()),
                    id(c.productId()), c.batchNo(), c.title(), c.description(), id(c.assigneeUserId()),
                    c.slaDueAt(), c.createdAt(), c.updatedAt(), c.closedAt(), c.version(),
                    c.queueCode(), slaState.name(),
                    actions == null ? null : actions.transitions().stream().map(e -> new TransitionView(e.toStatus(), e.toClosed())).toList(),
                    actions == null ? null : actions.canAssign(),
                    sharedWith);
        }

        /**
         * SPEC-CASE-11 R230/R231: the active shares as the caller may see them: all of them for the grower and staff, only
         * the caller's own org for a partner (partners never learn which other shops see a case, ACC-11).
         */
        static List<String> sharesFor(CaseRecord c, com.growercare.security.GcPrincipal principal) {
            if (principal.userType() == com.growercare.security.UserType.PARTNER) {
                return principal.partnerOrg().filter(c.activeShares()::contains).map(org -> List.of(Long.toString(org))).orElse(List.of());
            }
            return c.activeShares().stream().sorted().map(String::valueOf).toList();
        }
    }

    /** SPEC-CASE-09 D1: a transition the caller may take now; {@code closes} = the target status closes the case. */
    record TransitionView(String toStatus, boolean closes) { }

    record CasePage(List<CaseView> items, String nextCursor) { }

    record CommentView(String commentId, String caseId, String authorUserId, String authorRole, String visibility,
                       String body, String replyToCommentId, Instant createdAt, List<String> attachmentIds) {
        static CommentView of(CommentsAndSharing.CommentEntry entry) {
            var c = entry.comment();
            return new CommentView(id(c.commentId()), id(c.caseId()), id(c.authorUserId()), c.authorRole(),
                    c.visibility().name(), c.body(), id(c.replyToCommentId()), c.createdAt(),
                    entry.attachmentIds().stream().map(CaseDtos::id).toList());
        }
    }

    // ------------------------------------------------------------------------------------------------ attachments (SPEC-CASE-05)

    record RequestAttachmentRequest(
            @NotBlank @Size(max = 20) String kind,
            @NotBlank @Size(max = 100) String contentType,
            @NotNull Long sizeBytes,
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$") String sha256) { }

    record PresignedView(String method, String url, Map<String, String> headers, Instant expiresAt) {
        static PresignedView of(ObjectStore.PresignedRequest r) {
            return new PresignedView(r.method(), r.url().toString(), r.headers(), r.expiresAt());
        }
    }

    record AttachmentUploadView(String attachmentId, String status, PresignedView upload) { }

    /** No storage keys or bucket names leave the service (SPEC-CASE-05 §3). */
    record AttachmentView(String attachmentId, String caseId, String commentId, String kind, String contentType,
                          long sizeBytes, String status, String uploadedByUserId, Instant createdAt, Instant scannedAt) {
        static AttachmentView of(Attachment a) {
            return new AttachmentView(id(a.attachmentId()), id(a.caseId()), id(a.commentId()), a.kind(), a.contentType(),
                    a.sizeBytes(), a.status().name(), id(a.uploadedByUserId()), a.createdAt(), a.scannedAt());
        }
    }

    record AttachmentPage(List<AttachmentView> items) { }

    record DownloadView(String url, Instant expiresAt) { }

    record CommentPage(List<CommentView> items, String nextCursor) { }

    record ShareView(String caseId, String partnerOrgId, Instant consentGivenAt, Instant revokedAt) {
        static ShareView of(Share s) {
            return new ShareView(id(s.caseId()), id(s.partnerOrgId()), s.consentGivenAt(), s.revokedAt());
        }
    }

    record ActivityView(String activityId, String type, String fromStatus, String toStatus, String actorUserId,
                        String note, Instant occurredAt) {
        static ActivityView of(Activity a) {
            return new ActivityView(id(a.activityId()), a.type(), a.fromStatus(), a.toStatus(), id(a.actorUserId()),
                    a.note(), a.occurredAt());
        }
    }

    // ------------------------------------------------------------------------------------------------ parsing

    static String id(Long value) {
        return value == null ? null : Long.toString(value);
    }

    static String id(long value) {
        return Long.toString(value);
    }

    /**
     * R24: a body or query id. {@link #ID} also admits 19-digit values above {@code Long.MAX_VALUE}, so overflow is a
     * 400 on the field, never a 500 (found in grower-service review, applied here).
     */
    static long id(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ValidationException.of(field, "must be a positive 64-bit id");
        }
    }

    /** SPEC-CASE-07 §3: ids as strings; no grower data (D6). */
    record OverviewView(OverviewScope scope, Instant asOf, java.util.Map<String, Long> openByStatus, java.util.Map<String, Long> openByType,
                        OverviewSla sla, List<Workload> workload, long unassigned) {

        record OverviewScope(List<String> countryCodes, List<String> territoryIds) { }

        record OverviewSla(long atRisk, long breached) { }

        record Workload(String assigneeUserId, long open) { }

        static OverviewView of(com.growercare.casesvc.application.CaseQueries.Overview o) {
            var workload = o.workload().entrySet().stream()
                    .sorted(java.util.Map.Entry.<Long, Long>comparingByValue().reversed().thenComparing(java.util.Map.Entry.comparingByKey()))
                    .map(e -> new Workload(e.getKey().toString(), e.getValue())).toList();
            return new OverviewView(
                    new OverviewScope(o.countryCodes().stream().sorted().toList(), o.territoryIds().stream().sorted().map(String::valueOf).toList()),
                    o.asOf(), o.openByStatus(), o.openByType(), new OverviewSla(o.atRisk(), o.breached()), workload, o.unassigned());
        }
    }

    static Long optionalId(String raw, String field) {
        return raw == null ? null : id(raw, field);
    }

    /** Path ids that are malformed (including 19-digit overflow) are indistinguishable from unknown ones (R29). */
    static long pathIdOrNotFound(String raw, String resource) {
        if (raw == null || !raw.matches(ID)) {
            throw new NotFoundException(resource);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new NotFoundException(resource);
        }
    }

    static long caseIdOrNotFound(String raw) {
        return pathIdOrNotFound(raw, "Case");
    }

    /** {@code If-Match: "7"} (weak form accepted) → 7; anything else is a 400 on the header. */
    static int version(String ifMatch) {
        Matcher m = ETAG.matcher(ifMatch.strip());
        if (!m.matches()) {
            throw ValidationException.of("If-Match", "must be the ETag of the current version, e.g. \"3\"");
        }
        return Integer.parseInt(m.group(1));
    }

    static String etag(int version) {
        return "\"" + version + "\"";
    }

    static UUID uuid(String raw, String field) {
        if (raw == null || !UUID_TEXT.matcher(raw).matches()) {
            throw ValidationException.of(field, "must be a UUID");
        }
        return UUID.fromString(raw);
    }
}
