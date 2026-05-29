package com.bellgado.logistics_ted.web.dto;

/**
 * Response body for POST /api/login. The dashboard reads {@code token} and stores it client-side
 * to use as {@code Authorization: Bearer <token>} on subsequent /api/** requests.
 * {@code expiresIn} is in seconds; {@code user} matches the prior session-era /api/me payload.
 */
public record LoginResponse(String token, String tokenType, long expiresIn, UserResponse user) {
    public static LoginResponse bearer(String token, long expiresIn, UserResponse user) {
        return new LoginResponse(token, "Bearer", expiresIn, user);
    }
}
