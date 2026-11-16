package com.growercare.casesvc.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.casesvc.adapters.out.storage.S3ObjectStore;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SPEC-CASE-05 R115 / E123: presigned URLs point at the address clients can reach (S3_PUBLIC_ENDPOINT) while the
 * service talks to storage at its internal address (S3_ENDPOINT). Presigning is offline, so no S3 is needed.
 */
class PresignedEndpointTest {

    static AttachmentsConfig.AttachmentProperties props(String endpoint, String publicEndpoint) {
        return new AttachmentsConfig.AttachmentProperties(endpoint, publicEndpoint, "us-east-1", "key", "secret",
                "gc-media-staging", "gc-media-clean", "gc-media-quarantine", false, false);
    }

    /** endpoint, public endpoint, expected host:port in presigned URLs. */
    static Stream<Arguments> rows() {
        return Stream.of(
                argumentSet("CASE-E123a public endpoint wins", "http://seaweedfs:8333", "http://localhost:8333", "localhost:8333"),
                argumentSet("CASE-E123b no public endpoint: the internal one", "http://seaweedfs:8333", "", "seaweedfs:8333"));
    }

    @ParameterizedTest
    @MethodSource("rows")
    void presignedUrlsUseThePublicEndpoint(String endpoint, String publicEndpoint, String expectedAuthority) {
        var config = new AttachmentsConfig();
        var p = props(endpoint, publicEndpoint);
        try (var s3 = config.s3Client(p); var presigner = config.s3Presigner(p)) {
            var store = new S3ObjectStore(s3, presigner, new S3ObjectStore.Buckets("gc-media-staging", "gc-media-clean", "gc-media-quarantine"), false);
            var upload = store.presignUpload("in_s01/1/2", "image/jpeg", 10, "a".repeat(64), Duration.ofMinutes(5));
            var download = store.presignDownload("in_s01/1/2", Duration.ofSeconds(60));
            assertThat(upload.url().getAuthority()).isEqualTo(expectedAuthority);
            assertThat(download.url().getAuthority()).isEqualTo(expectedAuthority);
        }
    }
}
