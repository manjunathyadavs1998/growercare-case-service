package com.growercare.casesvc.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SPEC-PLAT-06 R82 (PLAT-E94): configuration that holds a secret never prints it. */
class MigrationPropertiesTest {

    @ParameterizedTest(name = "PLAT-E94 owner password {0} → {1}")
    @CsvSource(nullValues = "NULL", textBlock = """
            s3cret-owner-pw, ownerPassword=****
            NULL,            ownerPassword=null
            """)
    void theOwnerPasswordIsNeverPrinted(String password, String shown) {
        var props = new MigrationProperties(true, "gc_case_owner", password, "gc_case_app", null, false, 2);
        assertThat(props.toString()).contains(shown, "gc_case_owner").doesNotContain("s3cret");
        assertThat(props.ownerPassword()).isEqualTo(password);   // still usable by the migrator
    }

    @ParameterizedTest(name = "PLAT-E94 S3 keys {0}/{1} are never printed")
    @CsvSource(nullValues = "NULL", textBlock = """
            AKIA-secret-access, s3cret-key-value
            NULL,               NULL
            """)
    void theS3KeysAreNeverPrinted(String accessKey, String secretKey) {
        var props = new AttachmentsConfig.AttachmentProperties("http://s3.local", null, "us-east-1", accessKey, secretKey,
                "staging", "clean", "quarantine", true, false);
        assertThat(props.toString()).doesNotContain("AKIA-secret-access", "s3cret-key-value").contains("staging", "us-east-1");
        assertThat(props.secretKey()).isEqualTo(secretKey);
    }
}
