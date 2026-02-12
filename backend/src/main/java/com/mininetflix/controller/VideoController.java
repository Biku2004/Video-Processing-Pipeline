package com.mininetflix.controller;

import com.mininetflix.model.User;
import com.mininetflix.ratelimit.RateLimitService;
import com.mininetflix.service.VideoService;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Video API Contract (frontend must use exactly these):
 *
 * GET    /api/videos                        → List of user's videos
 * GET    /api/videos/{videoId}              → Single video metadata
 * POST   /api/videos/upload-url             → {filename, contentType, fileSize, title?} → {videoId, uploadUrl, s3Key, expiresInSeconds}
 * POST   /api/videos/{videoId}/confirm      → {fileSizeBytes?} → updated VideoDto
 * GET    /api/videos/{videoId}/stream       → {masterPlaylistUrl, thumbnailUrl, ...}
 * DELETE /api/videos/{videoId}              → 204 No Content
 * GET    /api/videos/rate-limit             → {uploadsToday, dailyLimit, remaining, tier}
 *
 * MediaConvert Webhook (CloudWatch → API Gateway → this endpoint):
 * POST   /api/videos/webhook/mediaconvert   → {jobId, status, progress}
 */
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
@Slf4j
public class VideoController {

    private final VideoService videoService;
    private final RateLimitService rateLimitService;

    /** GET /api/videos - Get all videos for authenticated user */
    @GetMapping
    public ResponseEntity<List<VideoService.VideoDto>> getUserVideos(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(videoService.getUserVideos(user));
    }

    /** GET /api/videos/{videoId} - Get single video metadata */
    @GetMapping("/{videoId}")
    public ResponseEntity<?> getVideo(
            @AuthenticationPrincipal User user,
            @PathVariable String videoId) {
        try {
            return ResponseEntity.ok(videoService.getVideo(user, videoId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/videos/upload-url
     * Body: { "filename": "video.mp4", "contentType": "video/mp4", "fileSize": 104857600, "title": "My Video" }
     * Returns: { "videoId": "...", "uploadUrl": "https://s3...", "s3Key": "...", "expiresInSeconds": 300 }
     */
    @PostMapping("/upload-url")
    public ResponseEntity<?> getUploadUrl(
            @AuthenticationPrincipal User user,
            @RequestBody UploadUrlRequest request) {
        try {
            VideoService.UploadUrlResult result = videoService.generateUploadUrl(
                    user,
                    request.filename(),
                    request.contentType(),
                    request.fileSize(),
                    request.title()
            );
            return ResponseEntity.ok(Map.of(
                    "videoId", result.videoId(),
                    "uploadUrl", result.uploadUrl(),
                    "s3Key", result.s3Key(),
                    "expiresInSeconds", result.expiresInSeconds(),
                    "userId", user.getId()
            ));
        } catch (RateLimitService.RateLimitExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", e.getMessage(), "error", "RATE_LIMIT_EXCEEDED"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/videos/{videoId}/confirm
     * Called by frontend AFTER S3 upload completes.
     * Body: { "fileSizeBytes": 104857600 } (optional)
     * Returns: Updated VideoDto
     */
    @PostMapping("/{videoId}/confirm")
    public ResponseEntity<?> confirmUpload(
            @AuthenticationPrincipal User user,
            @PathVariable String videoId,
            @RequestBody(required = false) ConfirmRequest request) {
        try {
            Long fileSizeBytes = (request != null) ? request.fileSizeBytes() : null;
            VideoService.VideoDto result = videoService.confirmUpload(user, videoId, fileSizeBytes);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * GET /api/videos/{videoId}/stream
     * Returns streaming URLs for a READY video.
     * Returns: { "masterPlaylistUrl": "...", "thumbnailUrl": "...", "durationSeconds": 120, ... }
     */
    @GetMapping("/{videoId}/stream")
    public ResponseEntity<?> getStreamingInfo(
            @AuthenticationPrincipal User user,
            @PathVariable String videoId) {
        try {
            VideoService.StreamingInfo info = videoService.getStreamingInfo(user, videoId);
            return ResponseEntity.ok(info);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", e.getMessage(), "error", "VIDEO_NOT_READY"));
        }
    }

    /** DELETE /api/videos/{videoId} - Soft delete */
    @DeleteMapping("/{videoId}")
    public ResponseEntity<?> deleteVideo(
            @AuthenticationPrincipal User user,
            @PathVariable String videoId) {
        try {
            videoService.deleteVideo(user, videoId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    /** GET /api/videos/rate-limit - Check user's rate limit status */
    @GetMapping("/rate-limit")
    public ResponseEntity<?> getRateLimitStatus(@AuthenticationPrincipal User user) {
        long todayUploads = videoService.getUserVideos(user).stream()
                .filter(v -> v.createdAt().toLocalDate().equals(java.time.LocalDate.now()))
                .count();
        int limit = rateLimitService.getDailyLimit(user);

        return ResponseEntity.ok(Map.of(
                "uploadsToday", todayUploads,
                "dailyLimit", limit,
                "remaining", Math.max(0, limit - todayUploads),
                "tier", user.getTier().name(),
                "maxFileSizeMb", rateLimitService.getMaxFileSizeMb(user)
        ));
    }

    /**
     * POST /api/videos/webhook/mediaconvert
     * Called by CloudWatch Events when MediaConvert job status changes.
     * Body: { "jobId": "...", "status": "COMPLETE|ERROR|PROGRESSING", "progress": 73 }
     */
    @PostMapping("/webhook/mediaconvert")
    public ResponseEntity<?> mediaConvertWebhook(@RequestBody MediaConvertWebhookRequest request) {
        log.info("MediaConvert webhook: jobId={}, status={}, progress={}",
                request.jobId(), request.status(), request.progress());
        videoService.updateVideoStatus(request.jobId(), request.status(), request.progress());
        return ResponseEntity.ok(Map.of("received", true));
    }

    // ==================== Request Records ====================

    record UploadUrlRequest(
            @NotBlank String filename,
            @NotBlank String contentType,
            @NotNull @Positive Long fileSize,
            String title
    ) {}

    record ConfirmRequest(Long fileSizeBytes) {}

    record MediaConvertWebhookRequest(String jobId, String status, Integer progress) {}
}
