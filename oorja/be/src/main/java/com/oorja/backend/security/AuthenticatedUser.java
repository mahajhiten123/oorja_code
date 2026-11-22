package com.oorja.backend.security;

public record AuthenticatedUser(Long userId, String email, String role) {}
