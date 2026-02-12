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
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.nio.file.Files;
import java.util.Base64;
import software.amazon.awssdk.services.cloudfront.model.CustomSignerRequest;
import software.amazon.awssdk.services.cloudfront.url.SignedUrl;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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

    @Value("${aws.cloudfront.key-pair-id}")
    private String cloudFrontKeyPairId;

    @Value("${aws.cloudfront.private-key-path}")
    private String cloudFrontPrivateKeyPath;

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
     * MediaConvert names files based on input filename, not "master.m3u8",
     * so we search S3 for the master playlist (.m3u8 without resolution suffix).
     */
    public String buildStreamingUrl(String outputPrefix) {
        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(outputBucket)
                    .prefix(outputPrefix)
                    .build();

            ListObjectsV2Response response = s3Client.listObjectsV2(listRequest);

            // Find the master playlist: ends with .m3u8 but NOT a variant (e.g., _480p.m3u8)
            String masterKey = response.contents().stream()
                    .map(S3Object::key)
                    .filter(key -> key.endsWith(".m3u8"))
                    .filter(key -> !key.matches(".*_\\d+p\\.m3u8$"))
                    .findFirst()
                    .orElse(outputPrefix + "master.m3u8"); // fallback

            log.info("Found master playlist: {}", masterKey);
            return cloudFrontDomain + "/" + masterKey;
        } catch (Exception e) {
            log.warn("Failed to find master playlist in {}: {}", outputPrefix, e.getMessage());
            return cloudFrontDomain + "/" + outputPrefix + "master.m3u8";
        }
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

    /**
     * Delete all objects in a folder (prefix).
     * Used for hard deletion of videos.
     */
    public void deleteFolder(String bucket, String prefix) {
        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response listResponse;
            do {
                listResponse = s3Client.listObjectsV2(listRequest);
                
                if (listResponse.hasContents()) {
                    List<ObjectIdentifier> objects = listResponse.contents().stream()
                            .map(os -> ObjectIdentifier.builder().key(os.key()).build())
                            .toList();

                    s3Client.deleteObjects(DeleteObjectsRequest.builder()
                            .bucket(bucket)
                            .delete(Delete.builder().objects(objects).build())
                            .build());
                    
                    log.info("Deleted {} objects from {}/{}", objects.size(), bucket, prefix);
                }

                listRequest = listRequest.toBuilder()
                        .continuationToken(listResponse.nextContinuationToken())
                        .build();
                
            } while (listResponse.isTruncated());
            
        } catch (Exception e) {
            log.error("Failed to delete folder {}/{}: {}", bucket, prefix, e.getMessage());
        }
    }

    /**
     * Sign the master playlist URL with CloudFront Custom Policy.
     * Allows access to "processed/{videoId}/*" so segments can be fetched.
     */
    public String signUrl(String masterPlaylistUrl) {
        try {
            // URL: https://d1.../processed/UUID/master.m3u8
            // Resource: https://d1.../processed/UUID/*
            String resourcePath = masterPlaylistUrl.substring(0, masterPlaylistUrl.lastIndexOf('/') + 1) + "*";
            
            Instant expirationDate = Instant.now().plus(6, ChronoUnit.HOURS);
            
            CloudFrontUtilities cloudFrontUtilities = CloudFrontUtilities.create();
            CustomSignerRequest customSignerRequest = CustomSignerRequest.builder()
                    .resourceUrl(resourcePath)
                    .privateKey(loadPrivateKey(cloudFrontPrivateKeyPath))
                    .keyPairId(cloudFrontKeyPairId)
                    .expirationDate(expirationDate)
                    .build();
            
            SignedUrl signedUrl = cloudFrontUtilities.getSignedUrlWithCustomPolicy(customSignerRequest);
            
            // Extract query params from the signed URL (which is the resource URL + query params)
            String signatureQuery = signedUrl.url().substring(signedUrl.url().indexOf('?') + 1);
            
            return masterPlaylistUrl + "?" + signatureQuery;
            
        } catch (Exception e) {
            log.error("Failed to sign URL. KeyPairId: {}, Path: {}", cloudFrontKeyPairId, cloudFrontPrivateKeyPath, e);
            return masterPlaylistUrl;
        }
    }

    private PrivateKey loadPrivateKey(String path) throws Exception {
        String keyContent = Files.readString(Paths.get(path));
        
        // Remove headers/footers and newlines
        String privateKeyPEM = keyContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        return keyFactory.generatePrivate(keySpec);
    }
}
