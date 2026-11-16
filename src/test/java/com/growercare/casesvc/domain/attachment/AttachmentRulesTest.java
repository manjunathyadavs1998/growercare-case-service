package com.growercare.casesvc.domain.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.casesvc.domain.attachment.AttachmentRules.TypeRule;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-CASE-05 D7 / R97 (E104): kind × content type × size, boundaries included. */
class AttachmentRulesTest {

    static final long MB = 1024L * 1024L;

    /** Same rows as common V6 (ref.attachment_type_rule). */
    static final List<TypeRule> SEED = Stream.of(
                    Stream.of("PHOTO", "PACK_PHOTO").flatMap(k -> Stream.of(
                            new TypeRule(k, "image/jpeg", 25 * MB), new TypeRule(k, "image/png", 25 * MB))),
                    Stream.of(new TypeRule("VIDEO", "video/mp4", 100 * MB)),
                    Stream.of("INVOICE", "PRESCRIPTION", "LAB_REPORT", "OTHER").flatMap(k -> Stream.of(
                            new TypeRule(k, "application/pdf", 25 * MB), new TypeRule(k, "image/jpeg", 25 * MB),
                            new TypeRule(k, "image/png", 25 * MB))))
            .flatMap(s -> s).toList();

    /** kind, content type, size, expected violated field (null = accepted). */
    static Stream<Arguments> rows() {
        return Stream.of(
                argumentSet("CASE-E103 photo jpeg 2 MB", "PHOTO", "image/jpeg", 2 * MB, null),
                argumentSet("CASE-E104a unknown kind", "SELFIE", "image/jpeg", 2 * MB, "kind"),
                argumentSet("CASE-E104b photo as pdf", "PHOTO", "application/pdf", 2 * MB, "contentType"),
                argumentSet("CASE-E104c photo exactly 25 MB", "PHOTO", "image/png", 25 * MB, null),
                argumentSet("CASE-E104d photo 25 MB + 1 byte", "PHOTO", "image/png", 25 * MB + 1, "sizeBytes"),
                argumentSet("CASE-E104e video exactly 100 MB", "VIDEO", "video/mp4", 100 * MB, null),
                argumentSet("CASE-E104f video 100 MB + 1 byte", "VIDEO", "video/mp4", 100 * MB + 1, "sizeBytes"),
                argumentSet("CASE-E104g empty file", "INVOICE", "application/pdf", 0L, "sizeBytes"),
                argumentSet("CASE-E104h video as jpeg", "VIDEO", "image/jpeg", MB, "contentType"),
                argumentSet("D7 prescription pdf", "PRESCRIPTION", "application/pdf", MB, null),
                argumentSet("D7 content type is case-insensitive", "LAB_REPORT", "Application/PDF", MB, null));
    }

    @ParameterizedTest
    @MethodSource("rows")
    void typeRules(String kind, String contentType, long size, String violatedField) {
        var violation = AttachmentRules.check(SEED, kind, contentType, size);
        assertThat(violation.map(AttachmentRules.Violation::field).orElse(null)).isEqualTo(violatedField);
    }
}
