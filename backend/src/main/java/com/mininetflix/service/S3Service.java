package com.mininetflix.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.input-bucket}")
    private String inputBucket;

    @Value("${aws.s3.output-bucket}")
    private String outputBucket;

    @Value("${aws.s3.presigned-url-expiry}")
    private int presignedUrlExpiry;

    @Value("${aws.cloudfront.domain}")
    private String cloudFrontDomain;

    /**
     * Generate a pre-signed PUT URL for direct client-to-S3 upload.
     * Backend never handles the video bytes - critical for scalability.
     */
    public String generatePresignedUploadUrl(String s3Key, String contentType,
                                              long fileSizeBytes, String videoId, String userId) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("video-id", videoId);
        metadata.put("user-id", userId);

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(inputBucket)
                .key(s3Key)
                .contentType(contentType)
                .metadata(metadata)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(presignedUrlExpiry))
                .putObjectRequest(objectRequest)
                .build();

        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);

        log.info("Generated presigned URL for key: {} (expires in {}s)", s3Key, presignedUrlExpiry);
        return presignedRequest.url().toString();
    }

    /**
     * Build the CloudFront URL for a processed video's master playlist.
     */
    public String buildStreamingUrl(String outputPrefix) {
        return cloudFrontDomain + "/" + outputPrefix + "master.m3u8";
    }

    /**
     * Build CloudFront URL for a thumbnail.
     */
    public String buildThumbnailUrl(String outputPrefix) {
        return cloudFrontDomain + "/" + outputPrefix + "thumbnail.jpg";
    }

    /**
     * Check if an object exists in S3 (used for idempotency check).
     */
    public boolean objectExists(String bucket, String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /**
     * Delete raw upload after processing (cost optimization).
     */
    public void deleteRawUpload(String s3Key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(inputBucket)
                    .key(s3Key)
                    .build());
            log.info("Deleted raw upload: {}", s3Key);
        } catch (Exception e) {
            log.warn("Failed to delete raw upload {}: {}", s3Key, e.getMessage());
        }
    }

    public String getInputBucket() { return inputBucket; }
    public String getOutputBucket() { return outputBucket; }
}
