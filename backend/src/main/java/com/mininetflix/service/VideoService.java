package com.mininetflix.service;

import com.mininetflix.model.User;
import com.mininetflix.model.Video;
import com.mininetflix.model.Video.VideoStatus;
import com.mininetflix.ratelimit.RateLimitService;
import com.mininetflix.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoService {

    private final VideoRepository videoRepository;
    private final S3Service s3Service;
    private final SqsService sqsService;
    private final MediaConvertService mediaConvertService;
    private final RateLimitService rateLimitService;

    // ==================== Upload Flow ====================

    /**
     * Step 1: Generate pre-signed URL for direct S3 upload.
     * Frontend uploads DIRECTLY to S3 - backend never handles video bytes.
     * Returns videoId and presigned URL.
     */
    @Transactional
    public UploadUrlResult generateUploadUrl(User user, String filename,
                                              String contentType, long fileSizeBytes, String title) {
        // Rate limiting checks
        rateLimitService.checkUploadLimit(user);
        rateLimitService.checkFileSizeLimit(user, fileSizeBytes);

        String sanitizedFilename = sanitizeFilename(filename);

        // Save video metadata to DB first — let JPA generate the UUID
        Video video = Video.builder()
                .title(title != null && !title.isBlank() ? title : extractTitleFromFilename(filename))
                .originalFilename(filename)
                .user(user)
                .status(VideoStatus.UPLOADED)
                .fileSizeBytes(fileSizeBytes)
                .build();

        video = videoRepository.save(video);

        // Now use the JPA-generated ID for S3 key
        String videoId = video.getId();
        String s3Key = "uploads/" + user.getId() + "/" + videoId + "/" + sanitizedFilename;
        video.setInputS3Key(s3Key);
        video.setOutputS3Prefix("processed/" + videoId + "/");
        videoRepository.save(video);

        // Generate presigned URL (5-min expiry)
        String uploadUrl = s3Service.generatePresignedUploadUrl(
                s3Key, contentType, fileSizeBytes, videoId, user.getId()
        );

        log.info("Generated upload URL for videoId: {}, user: {}", videoId, user.getUsername());

        return new UploadUrlResult(videoId, uploadUrl, s3Key, 300);
    }

    /**
     * Step 2: Frontend calls this after S3 upload completes.
     * Queues processing job in SQS → triggers encoding pipeline.
     * Idempotent: won't re-queue if already processing.
     */
    @Transactional
    public VideoDto confirmUpload(User user, String videoId, Long fileSizeBytes) {
        Video video = videoRepository.findByIdAndUserId(videoId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Video not found: " + videoId));

        // Idempotency check - don't re-queue if already processing
        if (video.getStatus() == VideoStatus.PROCESSING ||
            video.getStatus() == VideoStatus.READY) {
            log.info("Video {} already in status: {}, skipping re-queue", videoId, video.getStatus());
            return toDto(video);
        }

        // Update file size if provided
        if (fileSizeBytes != null) {
            video.setFileSizeBytes(fileSizeBytes);
        }

        // Transition: UPLOADED → QUEUED
        video.setStatus(VideoStatus.QUEUED);
        videoRepository.save(video);

        // Send to SQS for async processing
        // SQS decouples upload from processing - prevents overload, allows retries
        try {
            sqsService.sendVideoProcessingJob(
                    videoId, video.getInputS3Key(),
                    user.getId(), s3Service.getInputBucket()
            );

            // Immediately trigger MediaConvert (in production, SQS worker would do this)
            triggerMediaConvert(video);

        } catch (Exception e) {
            log.error("Failed to queue/process video {}: {}", videoId, e.getMessage());
            video.setStatus(VideoStatus.FAILED);
            video.setErrorMessage("Failed to start processing: " + e.getMessage());
            videoRepository.save(video);
        }

        return toDto(video);
    }

    /**
     * Trigger actual MediaConvert encoding job.
     * In production, this is triggered by an SQS worker/Lambda.
     */
    @Transactional
    public void triggerMediaConvert(Video video) {
        // Idempotency: don't create duplicate jobs
        if (video.getMediaConvertJobId() != null) {
            log.warn("Video {} already has MediaConvert job: {}", video.getId(), video.getMediaConvertJobId());
            return;
        }

        try {
            video.setStatus(VideoStatus.PROCESSING);
            video.setProcessingStartedAt(LocalDateTime.now());

            String jobId = mediaConvertService.createTranscodingJob(
                    video.getId(),
                    video.getInputS3Key(),
                    video.getOriginalResolution()
            );

            video.setMediaConvertJobId(jobId);
            videoRepository.save(video);

            log.info("MediaConvert job {} created for video {}", jobId, video.getId());

        } catch (Exception e) {
            video.setStatus(VideoStatus.FAILED);
            video.setErrorMessage(e.getMessage());
            video.setRetryCount(video.getRetryCount() + 1);
            videoRepository.save(video);
            log.error("Failed to create MediaConvert job for video {}: {}", video.getId(), e.getMessage());
        }
    }

    // ==================== Query Flow ====================

    public List<VideoDto> getUserVideos(User user) {
        return videoRepository
                .findByUserIdAndStatusNotOrderByCreatedAtDesc(user.getId(), VideoStatus.DELETED)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public VideoDto getVideo(User user, String videoId) {
        Video video = videoRepository.findByIdAndUserId(videoId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Video not found: " + videoId));
        return toDto(video);
    }

    /**
     * Get streaming URL for ready video.
     * Returns CloudFront HLS URL for adaptive bitrate streaming.
     * Dynamically resolves the master playlist URL from S3 to handle
     * cases where the stored URL might be stale or incorrect.
     */
    @Transactional
    public StreamingInfo getStreamingInfo(User user, String videoId) {
        Video video = videoRepository.findByIdAndUserId(videoId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Video not found: " + videoId));

        if (video.getStatus() != VideoStatus.READY) {
            throw new IllegalStateException("Video is not ready for streaming. Status: " + video.getStatus());
        }

        // Always resolve the master playlist URL dynamically from S3
        // This fixes stale/incorrect URLs stored from earlier buggy detection logic
        String resolvedUrl = s3Service.buildStreamingUrl(video.getOutputS3Prefix());
        if (!resolvedUrl.equals(video.getMasterPlaylistUrl())) {
            log.info("Updating stale master playlist URL for video {}: {} -> {}",
                    videoId, video.getMasterPlaylistUrl(), resolvedUrl);
            video.setMasterPlaylistUrl(resolvedUrl);
            videoRepository.save(video);
        }

        return new StreamingInfo(
                s3Service.signUrl(resolvedUrl),
                s3Service.signUrl(video.getThumbnailUrl()),
                video.getDurationSeconds(),
                video.isHas1080p(),
                video.isHas720p(),
                video.isHas480p()
        );
    }

    // ==================== Status Update (called by webhook/polling) ====================

    /**
     * Update video status from MediaConvert webhook callback.
     * Called when CloudWatch event fires on job completion.
     */
    @Transactional
    public void updateVideoStatus(String jobId, String status, Integer progress) {
        videoRepository.findByMediaConvertJobId(jobId).ifPresent(video -> {
            switch (status.toUpperCase()) {
                case "COMPLETE" -> {
                    video.setStatus(VideoStatus.READY);
                    video.setCompletedAt(LocalDateTime.now());
                    video.setMasterPlaylistUrl(s3Service.buildStreamingUrl(video.getOutputS3Prefix()));
                    video.setThumbnailUrl(s3Service.buildThumbnailUrl(video.getOutputS3Prefix()));
                    video.setHas480p(true);
                    video.setHas720p(true);
                    video.setHas1080p(video.getOriginalResolution() != null &&
                            video.getOriginalResolution().contains("1080"));
                    // Cost optimization: delete raw upload after successful processing
                    s3Service.deleteRawUpload(video.getInputS3Key());
                    log.info("Video {} is now READY for streaming", video.getId());
                }
                case "ERROR" -> {
                    video.setStatus(VideoStatus.FAILED);
                    video.setErrorMessage("MediaConvert job failed. Will retry automatically.");
                    video.setRetryCount(video.getRetryCount() + 1);
                    log.error("MediaConvert job {} failed for video {}", jobId, video.getId());
                }
                case "PROGRESSING" -> {
                    if (progress != null) {
                        log.debug("Video {} processing: {}%", video.getId(), progress);
                    }
                }
            }
            videoRepository.save(video);
        });
    }

    // ==================== Delete ====================

    @Transactional
    public void deleteVideo(User user, String videoId) {
        Video video = videoRepository.findByIdAndUserId(videoId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Video not found: " + videoId));

        // Hard delete from S3 to save costs
        if (video.getOutputS3Prefix() != null) {
            s3Service.deleteFolder(s3Service.getOutputBucket(), video.getOutputS3Prefix());
            log.info("Deleted S3 output folder: {}", video.getOutputS3Prefix());
        }

        if (video.getInputS3Key() != null) {
            s3Service.deleteRawUpload(video.getInputS3Key());
        }

        video.setStatus(VideoStatus.DELETED);
        // Clear URLs to prevent access
        video.setMasterPlaylistUrl(null);
        video.setThumbnailUrl(null);
        
        videoRepository.save(video);
        log.info("Hard-deleted video files and soft-deleted DB record {} by user {}", videoId, user.getUsername());
    }

    // ==================== Scheduled Jobs ====================

    /**
     * Poll stuck jobs every 2 minutes (fallback if webhook missed).
     */
    @Scheduled(fixedDelay = 120_000)
    public void pollProcessingJobs() {
        List<Video> processingVideos = videoRepository.findByStatusIn(
                List.of(VideoStatus.PROCESSING)
        );

        for (Video video : processingVideos) {
            if (video.getMediaConvertJobId() == null) continue;

            try {
                var status = mediaConvertService.getJobStatus(video.getMediaConvertJobId());
                updateVideoStatus(video.getMediaConvertJobId(), status.toString(),
                        mediaConvertService.getJobProgress(video.getMediaConvertJobId()));
            } catch (Exception e) {
                log.warn("Failed to poll job status for video {}: {}", video.getId(), e.getMessage());
            }
        }
    }

    // ==================== Helpers ====================

    private VideoDto toDto(Video video) {
        return new VideoDto(
                video.getId(),
                video.getTitle(),
                video.getOriginalFilename(),
                video.getStatus().name(),
                video.getMasterPlaylistUrl(),
                video.getThumbnailUrl(),
                video.getFileSizeBytes(),
                video.getDurationSeconds(),
                video.getOriginalResolution(),
                video.isHas1080p(),
                video.isHas720p(),
                video.isHas480p(),
                video.getErrorMessage(),
                video.getRetryCount(),
                video.getCreatedAt(),
                video.getUpdatedAt(),
                video.getCompletedAt(),
                video.getUser().getId(),
                video.getUser().getUsername()
        );
    }

    private String sanitizeFilename(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
    }

    private String extractTitleFromFilename(String filename) {
        if (filename == null) return "Untitled";
        int dotIndex = filename.lastIndexOf('.');
        String name = dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
        return name.replace("_", " ").replace("-", " ").trim();
    }

    // ==================== Inner DTOs (returned by service) ====================

    public record UploadUrlResult(String videoId, String uploadUrl, String s3Key, int expiresInSeconds) {}

    public record VideoDto(
            String id, String title, String originalFilename,
            String status, String masterPlaylistUrl, String thumbnailUrl,
            Long fileSizeBytes, Long durationSeconds, String originalResolution,
            boolean has1080p, boolean has720p, boolean has480p,
            String errorMessage, int retryCount,
            java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt,
            java.time.LocalDateTime completedAt, String userId, String username
    ) {}

    public record StreamingInfo(
            String masterPlaylistUrl, String thumbnailUrl,
            Long durationSeconds, boolean has1080p, boolean has720p, boolean has480p
    ) {}
}
