package com.growercare.casesvc.domain.attachment;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** SPEC-CASE-05 D7: which file kinds and types are accepted, and how many attachments a case may hold. */
public final class AttachmentRules {

    /** One allowed (kind, content type) pair and its size limit; rows of {@code ref.attachment_type_rule}. */
    public record TypeRule(String kind, String contentType, long maxBytes) { }

    /** The first rule an upload request breaks: the request field and a message for the 400 response. */
    public record Violation(String field, String message) { }

    /** R99: live attachments per case. */
    public static final int MAX_LIVE_PER_CASE = 20;
    /** R112: attachments one comment may reference. */
    public static final int MAX_PER_COMMENT = 10;

    public static final Set<String> KINDS = Set.of("PHOTO", "VIDEO", "PACK_PHOTO", "INVOICE", "PRESCRIPTION", "LAB_REPORT", "OTHER");

    private AttachmentRules() { }

    /** R97: kind known, content type allowed for the kind, 0 &lt; size ≤ the kind's limit for that type. */
    public static Optional<Violation> check(List<TypeRule> rules, String kind, String contentType, long sizeBytes) {
        if (kind == null || !KINDS.contains(kind)) {
            return Optional.of(new Violation("kind", "must be one of " + KINDS.stream().sorted().toList()));
        }
        var type = normalise(contentType);
        var rule = rules.stream().filter(r -> r.kind().equals(kind) && r.contentType().equals(type)).findFirst();
        if (rule.isEmpty()) {
            return Optional.of(new Violation("contentType", "is not allowed for " + kind + ": "
                    + rules.stream().filter(r -> r.kind().equals(kind)).map(TypeRule::contentType).sorted().toList()));
        }
        if (sizeBytes <= 0 || sizeBytes > rule.get().maxBytes()) {
            return Optional.of(new Violation("sizeBytes", "must be between 1 and " + rule.get().maxBytes()));
        }
        return Optional.empty();
    }

    /** Content types are compared case-insensitively and without parameters ({@code image/jpeg; q=1}). */
    public static String normalise(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }
}
