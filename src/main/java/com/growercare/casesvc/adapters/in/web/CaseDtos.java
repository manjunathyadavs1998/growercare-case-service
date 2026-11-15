package com.growercare.casesvc.adapters.in.web;

import com.growercare.casesvc.domain.CaseChildren.Activity;
import com.growercare.casesvc.domain.CaseChildren.Comment;
import com.growercare.casesvc.domain.CaseChildren.Share;
import com.growercare.casesvc.domain.CaseRecord;
import com.growercare.casesvc.domain.CaseRules;
import com.growercare.casesvc.domain.SlaRules;
import com.growercare.common.error.NotFoundException;
import com.growercare.common.error.ValidationException;
import com.growercare.security.PiiClearance;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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
            @Pattern(regexp = ID) String replyToCommentId) { }

    record CaseView(
            String caseId, String caseNo, String caseType, String status, String priority,
            String countryCode, String territoryId, String buCode,
            String growerId, String growerName, String growerPhoneLast4, String farmId,
            String raisedByUserId, String raisedChannel, String raisedViaPartnerOrgId,
            String productId, String batchNo, String title, String description, String assigneeUserId,
            Instant slaDueAt, Instant createdAt, Instant updatedAt, Instant closedAt, int version,
            String queueCode, String slaState) {

        /** R30: the grower's name is masked unless the caller has FULL clearance; the phone never leaves. */
        static CaseView of(CaseRecord c, PiiClearance clearance, SlaRules.SlaState slaState) {
            return new CaseView(
                    id(c.caseId()), c.caseNo().formatted(), c.caseType(), c.status(), c.priority(),
                    c.countryCode(), id(c.territoryId()), c.buCode(),
                    id(c.growerId()), clearance == PiiClearance.FULL ? c.growerName() : CaseRules.maskName(c.growerName()),
                    c.growerPhoneLast4(), id(c.farmId()),
                    id(c.raisedByUserId()), c.raisedChannel(), id(c.raisedViaPartnerOrgId()),
                    id(c.productId()), c.batchNo(), c.title(), c.description(), id(c.assigneeUserId()),
                    c.slaDueAt(), c.createdAt(), c.updatedAt(), c.closedAt(), c.version(),
                    c.queueCode(), slaState.name());
        }
    }

    record CasePage(List<CaseView> items, String nextCursor) { }

    record CommentView(String commentId, String caseId, String authorUserId, String authorRole, String visibility,
                       String body, String replyToCommentId, Instant createdAt) {
        static CommentView of(Comment c) {
            return new CommentView(id(c.commentId()), id(c.caseId()), id(c.authorUserId()), c.authorRole(),
                    c.visibility().name(), c.body(), id(c.replyToCommentId()), c.createdAt());
        }
    }

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

    static Long optionalId(String value) {
        return value == null ? null : Long.parseLong(value);
    }

    /** Path ids that are malformed are indistinguishable from unknown ones (SPEC-CASE-02 R29). */
    static long caseIdOrNotFound(String raw) {
        if (raw == null || !raw.matches(ID)) {
            throw new NotFoundException("Case");
        }
        return Long.parseLong(raw);
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
