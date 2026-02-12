package com.mininetflix.dto;

import lombok.*;

import java.time.LocalDateTime;

public class RateLimitDto {

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
