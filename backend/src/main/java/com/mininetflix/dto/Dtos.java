package com.mininetflix.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mininetflix.model.Video.VideoStatus;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

// ===================== AUTH DTOs =====================

public class AuthDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterRequest {
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be 3-50 characters")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username can only contain letters, numbers, underscore")
        private String username;

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        private String password;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @NotBlank(message = "Password is required")
        private String password;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthResponse {
        private String token;
        private String userId;
        private String username;
        private String email;
        private String tier;
        private String message;
    }
}

// ===================== VIDEO DTOs =====================

class VideoDto {

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
        @Max(value = 5368709120L, message = "File size cannot exceed 5GB") // 5GB
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
        // Optional: frontend can send additional metadata
        private Long fileSizeBytes;
        private String title;
    }
}

// ===================== GENERIC RESPONSE =====================

class ApiResponse {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Success<T> {
        private boolean success = true;
        private String message;
        private T data;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Error {
        private boolean success = false;
        private String message;
        private String error;
        private int statusCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Page<T> {
        private List<T> content;
        private int page;
        private int size;
        private long total;
        private boolean hasMore;
    }
}

// ===================== RATE LIMIT DTO =====================

class RateLimitDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Status {
        private int uploadsToday;
        private int dailyLimit;
        private int remaining;
        private String tier;
        private LocalDateTime resetTime;
    }
}
