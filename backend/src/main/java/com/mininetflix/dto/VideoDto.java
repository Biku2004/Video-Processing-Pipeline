package com.mininetflix.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mininetflix.model.Video.VideoStatus;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDateTime;

public class VideoDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadUrlRequest {
        @NotBlank(message = "Filename is required")
        private String filename;

        @NotBlank(message = "Content type is required")
        @Pattern(regexp = "video/.*", message = "Only video files are allowed")
        private String contentType;

        @NotNull(message = "File size is required")
        @Positive(message = "File size must be positive")
        @Max(value = 5368709120L, message = "File size cannot exceed 5GB")
        private Long fileSize;

        @Size(max = 200, message = "Title too long")
        private String title;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadUrlResponse {
        private String videoId;
        private String uploadUrl;
        private String s3Key;
        private int expiresInSeconds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VideoResponse {
        private String id;
        private String title;
        private String originalFilename;
        private VideoStatus status;
        private String masterPlaylistUrl;
        private String thumbnailUrl;
        private Long fileSizeBytes;
        private Long durationSeconds;
        private String originalResolution;
        private boolean has1080p;
        private boolean has720p;
        private boolean has480p;
        private String errorMessage;
        private int retryCount;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime completedAt;
        private String userId;
        private String username;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfirmUploadRequest {
        private Long fileSizeBytes;
        private String title;
    }
}
