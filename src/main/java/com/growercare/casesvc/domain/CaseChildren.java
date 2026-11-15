package com.growercare.casesvc.domain;

import java.time.Instant;

/** Rows that hang off a case (comments, shares, activity). */
public final class CaseChildren {

    private CaseChildren() { }

    public record Comment(long caseId, long commentId, long authorUserId, String authorRole, CommentVisibility visibility,
                          String body, Long replyToCommentId, Instant createdAt) { }

    public record Share(long caseId, long partnerOrgId, Instant consentGivenAt, Instant revokedAt) {
        public boolean isActive() {
            return revokedAt == null;
        }
    }

    public record Activity(long activityId, String type, String fromStatus, String toStatus, long actorUserId,
                           String note, Instant occurredAt) { }
}
