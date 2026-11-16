package com.growercare.casesvc.application.port;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;

/**
 * SPEC-CASE-05 D2: S3-style object storage with three areas per cell. Bucket names are configuration, never input.
 * Keys are ids only ({@code Attachment.objectKey}).
 */
public interface ObjectStore {

    enum Area { STAGING, CLEAN, QUARANTINE }

    /** A presigned request the client performs itself; {@code headers} must be sent exactly as given. */
    record PresignedRequest(String method, URI url, Map<String, String> headers, Instant expiresAt) { }

    /** D3: PUT into staging, signed for the exact content type, length and SHA-256. */
    PresignedRequest presignUpload(String key, String contentType, long sizeBytes, String sha256Hex, Duration ttl);

    /** D10: GET from the clean area, downloaded as an attachment (never rendered inline). */
    PresignedRequest presignDownload(String key, Duration ttl);

    /** HEAD: size in bytes, or empty when the object does not exist. */
    OptionalLong size(Area area, String key);

    /** Downloads into a temporary file the caller deletes. */
    Path download(Area area, String key);

    void upload(Area area, String key, Path file, String contentType);

    /** Idempotent: deleting a missing object is not an error. */
    void delete(Area area, String key);
}
