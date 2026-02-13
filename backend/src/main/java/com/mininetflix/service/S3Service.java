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
     * so we search S3 for the master playlist.
     *
     * Detection strategy: The master playlist's base name is always a prefix of
     * variant playlist names (e.g., master: "video.m3u8", variants: "video_480p.m3u8").
     * We find the .m3u8 whose base name is a prefix of at least one other .m3u8.
     * If only one .m3u8 exists, that IS the master (single-resolution output).
     */
    public String buildStreamingUrl(String outputPrefix) {
        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(outputBucket)
                    .prefix(outputPrefix)
                    .build();

            ListObjectsV2Response response = s3Client.listObjectsV2(listRequest);

            List<String> m3u8Keys = response.contents().stream()
                    .map(S3Object::key)
                    .filter(key -> key.endsWith(".m3u8"))
                    .toList();

            String masterKey;
            if (m3u8Keys.size() == 1) {
                // Only one playlist — it's both the master and sole variant
                masterKey = m3u8Keys.get(0);
            } else {
                // The master's base name (without .m3u8) is a prefix of variant names.
                // e.g., master: "video_2160p.m3u8" → base: "video_2160p"
                //        variant: "video_2160p_480p.m3u8" starts with "video_2160p"
                masterKey = m3u8Keys.stream()
                        .filter(candidate -> {
                            String base = candidate.substring(0, candidate.length() - ".m3u8".length());
                            return m3u8Keys.stream()
                                    .anyMatch(other -> !other.equals(candidate) && other.startsWith(base + "_"));
                        })
                        .findFirst()
                        .orElse(m3u8Keys.stream()  // secondary fallback: shortest name
                                .min((a, b) -> Integer.compare(a.length(), b.length()))
                                .orElse(outputPrefix + "master.m3u8"));
            }

            log.info("Found master playlist: {} (from {} .m3u8 files)", masterKey, m3u8Keys.size());
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
