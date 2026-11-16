package com.growercare.casesvc.adapters.out.storage;

import com.growercare.casesvc.application.port.ObjectStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** {@link ObjectStore} on the S3 API (AWS S3 in the cloud, SeaweedFS locally). SPEC-CASE-05 D2, D3, D10. */
public class S3ObjectStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStore.class);
    /** Signed headers the HTTP client sets itself; the caller must not send them explicitly. */
    private static final Set<String> CLIENT_MANAGED = Set.of("host", "content-length");

    public record Buckets(String staging, String clean, String quarantine) { }

    private final S3Client s3;
    private final S3Presigner presigner;
    private final Buckets buckets;
    private final boolean signChecksum;

    public S3ObjectStore(S3Client s3, S3Presigner presigner, Buckets buckets, boolean signChecksum) {
        this.s3 = s3;
        this.presigner = presigner;
        this.buckets = buckets;
        this.signChecksum = signChecksum;
    }

    @Override
    public PresignedRequest presignUpload(String key, String contentType, long sizeBytes, String sha256Hex, Duration ttl) {
        var put = PutObjectRequest.builder().bucket(buckets.staging()).key(key).contentType(contentType).contentLength(sizeBytes);
        if (signChecksum) {
            put.checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex)));
        }
        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl).putObjectRequest(put.build()).build());
        return toPresigned(presigned);
    }

    @Override
    public PresignedRequest presignDownload(String key, Duration ttl) {
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(buckets.clean()).key(key)
                        .responseContentDisposition("attachment").build())
                .build());
        return toPresigned(presigned);
    }

    @Override
    public OptionalLong size(Area area, String key) {
        try {
            return OptionalLong.of(s3.headObject(HeadObjectRequest.builder().bucket(bucket(area)).key(key).build()).contentLength());
        } catch (NoSuchKeyException e) {
            return OptionalLong.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return OptionalLong.empty();
            }
            throw e;
        }
    }

    @Override
    public Path download(Area area, String key) {
        try {
            var target = Files.createTempFile("gc-attachment-", ".bin");
            Files.delete(target);                                   // the SDK refuses to overwrite an existing file
            s3.getObject(GetObjectRequest.builder().bucket(bucket(area)).key(key).build(), target);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void upload(Area area, String key, Path file, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket(area)).key(key).contentType(contentType).build(),
                RequestBody.fromFile(file));
    }

    @Override
    public void delete(Area area, String key) {
        try {
            s3.deleteObject(b -> b.bucket(bucket(area)).key(key));
        } catch (NoSuchKeyException e) {
            log.debug("object already gone");
        }
    }

    /** Local/test only ({@code gc.attachments.create-buckets}); production buckets come from gc-infra. */
    public void ensureBuckets() {
        for (var bucket : new String[] {buckets.staging(), buckets.clean(), buckets.quarantine()}) {
            try {
                s3.headBucket(b -> b.bucket(bucket));
            } catch (NoSuchBucketException e) {
                s3.createBucket(b -> b.bucket(bucket));
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    throw e;
                }
                s3.createBucket(b -> b.bucket(bucket));
            }
        }
    }

    private String bucket(Area area) {
        return switch (area) {
            case STAGING -> buckets.staging();
            case CLEAN -> buckets.clean();
            case QUARANTINE -> buckets.quarantine();
        };
    }

    private static PresignedRequest toPresigned(software.amazon.awssdk.awscore.presigner.PresignedRequest presigned) {
        var headers = new LinkedHashMap<String, String>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!CLIENT_MANAGED.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                headers.put(name, String.join(",", values));
            }
        });
        try {
            return new PresignedRequest(presigned.httpRequest().method().name(), presigned.url().toURI(),
                    Map.copyOf(headers), presigned.expiration());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("presigned URL is not a URI", e);
        }
    }
}
