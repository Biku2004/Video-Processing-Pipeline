package com.mininetflix.dto;

import lombok.*;

import java.util.List;

public class ApiResponse {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Success<T> {
        @Builder.Default
        private boolean success = true;
        private String message;
        private T data;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Error {
        @Builder.Default
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
