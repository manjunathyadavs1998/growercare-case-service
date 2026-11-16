package com.growercare.casesvc.config;

import com.growercare.casesvc.adapters.out.storage.S3ObjectStore;
import com.growercare.casesvc.application.ScanPipeline;
import com.growercare.casesvc.domain.attachment.EicarScanner;
import com.growercare.casesvc.domain.attachment.MalwareScanner;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * SPEC-CASE-05 D2/D5: object storage and scanner wiring. In AWS leave {@code endpoint} and the keys empty (IRSA
 * credentials, regional endpoint); locally they point at SeaweedFS.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AttachmentsConfig.AttachmentProperties.class)
public class AttachmentsConfig {

    private static final Logger log = LoggerFactory.getLogger(AttachmentsConfig.class);

    /**
     * {@code gc.attachments.*}.
     *
     * @param endpoint       S3 endpoint override (local); empty = AWS regional endpoint
     * @param signChecksum   sign {@code x-amz-checksum-sha256} into upload URLs (AWS enforces it); the scan job
     *                       re-verifies the SHA-256 either way (R105)
     * @param createBuckets  create the three buckets at startup (local and tests only)
     */
    @ConfigurationProperties("gc.attachments")
    public record AttachmentProperties(String endpoint, String region, String accessKey, String secretKey,
                                       String stagingBucket, String cleanBucket, String quarantineBucket,
                                       boolean signChecksum, boolean createBuckets) {
        public AttachmentProperties {
            region = region == null || region.isBlank() ? "us-east-1" : region;
        }

        boolean hasEndpoint() {
            return endpoint != null && !endpoint.isBlank();
        }
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(AttachmentProperties props) {
        var builder = S3Client.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentials(props))
                // S3-compatible stores (SeaweedFS) reject the SDK's default CRC checksums; integrity is checked by R105.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (props.hasEndpoint()) {
            builder.endpointOverride(URI.create(props.endpoint())).forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(AttachmentProperties props) {
        var builder = S3Presigner.builder().region(Region.of(props.region())).credentialsProvider(credentials(props));
        if (props.hasEndpoint()) {
            builder.endpointOverride(URI.create(props.endpoint()))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    @Bean
    S3ObjectStore objectStore(S3Client s3, S3Presigner presigner, AttachmentProperties props) {
        var store = new S3ObjectStore(s3, presigner,
                new S3ObjectStore.Buckets(props.stagingBucket(), props.cleanBucket(), props.quarantineBucket()), props.signChecksum());
        if (props.createBuckets()) {
            store.ensureBuckets();
        }
        return store;
    }

    /** D5: the EICAR test scanner until a real one (ClamAV / GuardDuty) is wired at the AWS stage. */
    @Bean
    MalwareScanner malwareScanner() {
        log.warn("Attachment scanning uses the EICAR test scanner (SPEC-CASE-05 D5): not a malware scanner, never for production");
        return new EicarScanner();
    }

    @Bean
    ScanPipeline scanPipeline(MalwareScanner scanner) {
        return new ScanPipeline(scanner);
    }

    private static AwsCredentialsProvider credentials(AttachmentProperties props) {
        if (props.accessKey() == null || props.accessKey().isBlank()) {
            return DefaultCredentialsProvider.builder().build();
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
    }
}
