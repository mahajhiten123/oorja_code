package com.oorja.backend.dto.response;

public record ApiMessageResponse(boolean success, String message) {
    public static ApiMessageResponse ok(String message) {
        return new ApiMessageResponse(true, message);
    }
    public static ApiMessageResponse fail(String message) {
        return new ApiMessageResponse(false, message);
    }
}
