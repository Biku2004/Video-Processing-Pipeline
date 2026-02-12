package com.mininetflix.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "videos")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(name = "original_filename")
    private String originalFilename;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private VideoStatus status = VideoStatus.UPLOADED;

    // S3 paths
    @Column(name = "input_s3_key")
    private String inputS3Key;

    @Column(name = "output_s3_prefix")
    private String outputS3Prefix;

    // Streaming URLs (served via CloudFront)
    @Column(name = "master_playlist_url")
    private String masterPlaylistUrl;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    // Encoding job tracking (idempotency)
    @Column(name = "mediaconvert_job_id")
    private String mediaConvertJobId;

    // File metadata
    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "original_resolution")
    private String originalResolution;

    // Available quality variants
    @Column(name = "has_1080p")
    @Builder.Default
    private boolean has1080p = false;

    @Column(name = "has_720p")
    @Builder.Default
    private boolean has720p = false;

    @Column(name = "has_480p")
    @Builder.Default
    private boolean has480p = false;

    // Error info
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "retry_count")
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum VideoStatus {
        UPLOADED,      // File received in S3, waiting for processing
        QUEUED,        // Message sent to SQS
        PROCESSING,    // MediaConvert job running
        READY,         // All variants ready, streaming available
        FAILED,        // Processing failed (check retryCount)
        DELETED        // Soft delete
    }
}
