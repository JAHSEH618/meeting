package com.meeting.api.adapter.auth;

import com.meeting.api.client.auth.AuthFacade;
import com.meeting.api.client.auth.AuthUserDTO;
import com.meeting.api.client.auth.LoginCommand;
import com.meeting.api.client.auth.LoginResultDTO;
import com.meeting.api.client.auth.RefreshResultDTO;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.ErrorInfo;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthFacade authFacade;

    public AuthController(AuthFacade authFacade) {
        this.authFacade = authFacade;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResultDTO>> login(
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
        @RequestBody LoginRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse response
    ) {
        LoginResultDTO result = authFacade.login(new LoginCommand(request.username(), request.password(), requestId, traceId));

        // Determine if we're on HTTPS or HTTP
        boolean isSecure = "https".equalsIgnoreCase(servletRequest.getScheme()) ||
                          "https".equalsIgnoreCase(servletRequest.getHeader("X-Forwarded-Proto"));

        // Set HttpOnly refresh token cookie (30 days)
        Cookie refreshCookie = new Cookie("REFRESH_TOKEN", result.refreshTokenId());
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(isSecure);  // Only secure on HTTPS
        refreshCookie.setPath("/api/auth");
        refreshCookie.setMaxAge(30 * 24 * 60 * 60);
        response.addCookie(refreshCookie);

        // Set CSRF token cookie (accessible to JS for X-CSRF-Token header)
        String csrfToken = java.util.UUID.randomUUID().toString();
        Cookie csrfCookie = new Cookie("XSRF-TOKEN", csrfToken);
        csrfCookie.setSecure(isSecure);  // Only secure on HTTPS
        csrfCookie.setPath("/api");
        csrfCookie.setMaxAge(30 * 24 * 60 * 60);
        response.addCookie(csrfCookie);

        // Don't expose refreshTokenId in response body
        return ResponseEntity.ok(ApiResponse.ok(
            new LoginResultDTO(result.accessToken(), result.expiresAt(), result.user(), null),
            requestId, traceId
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @CookieValue(value = "REFRESH_TOKEN", required = false) String refreshToken,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
        HttpServletResponse response
    ) {
        if (authorization != null && !authorization.isBlank()) {
            authFacade.logout(authorization);
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            authFacade.logoutRefreshToken(refreshToken);
        }

        // Clear cookies
        Cookie refreshCookie = new Cookie("REFRESH_TOKEN", null);
        refreshCookie.setHttpOnly(true);
        refreshCookie.setPath("/api/auth");
        refreshCookie.setMaxAge(0);
        response.addCookie(refreshCookie);

        Cookie csrfCookie = new Cookie("XSRF-TOKEN", null);
        csrfCookie.setPath("/api");
        csrfCookie.setMaxAge(0);
        response.addCookie(csrfCookie);

        return ResponseEntity.ok(ApiResponse.ok(null, requestId, traceId));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResultDTO>> refresh(
        @CookieValue(value = "REFRESH_TOKEN", required = false) String refreshToken,
        @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
        @CookieValue(value = "XSRF-TOKEN", required = false) String csrfCookie,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
        HttpServletRequest servletRequest,
        HttpServletResponse response
    ) {
        // Validate CSRF double-submit
        if (csrfToken == null || csrfCookie == null || !csrfToken.equals(csrfCookie)) {
            return ResponseEntity.status(401).body(ApiResponse.failed(
                ErrorInfo.of(ErrorCode.CSRF_TOKEN_INVALID, "CSRF token validation failed", false),
                requestId, traceId
            ));
        }

        if (refreshToken == null) {
            return ResponseEntity.status(401).body(ApiResponse.failed(
                ErrorInfo.of(ErrorCode.REFRESH_TOKEN_INVALID, "refresh token required", false),
                requestId, traceId
            ));
        }

        try {
            RefreshResultDTO result = authFacade.refresh(refreshToken, csrfToken);

            // Determine if we're on HTTPS or HTTP
            boolean isSecure = "https".equalsIgnoreCase(servletRequest.getScheme()) ||
                              "https".equalsIgnoreCase(servletRequest.getHeader("X-Forwarded-Proto"));

            // Rotate CSRF token
            String newCsrfToken = java.util.UUID.randomUUID().toString();
            Cookie newCsrfCookie = new Cookie("XSRF-TOKEN", newCsrfToken);
            newCsrfCookie.setSecure(isSecure);  // Only secure on HTTPS
            newCsrfCookie.setPath("/api");
            newCsrfCookie.setMaxAge(30 * 24 * 60 * 60);
            response.addCookie(newCsrfCookie);

            return ResponseEntity.ok(ApiResponse.ok(result, requestId, traceId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(ApiResponse.failed(
                ErrorInfo.of(ErrorCode.REFRESH_TOKEN_INVALID, e.getMessage(), false),
                requestId, traceId
            ));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthUserDTO>> me(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId
    ) {
        if (authorization == null || authorization.isBlank()) {
            return ResponseEntity.status(401).body(ApiResponse.failed(
                com.meeting.api.client.common.ErrorInfo.of(com.meeting.api.client.common.ErrorCode.AUTH_REQUIRED, "authentication required", false),
                requestId,
                traceId
            ));
        }
        return authFacade.me(authorization)
            .map(user -> ResponseEntity.ok(ApiResponse.ok(user, requestId, traceId)))
            .orElseGet(() -> ResponseEntity.status(401).body(ApiResponse.failed(
                com.meeting.api.client.common.ErrorInfo.of(com.meeting.api.client.common.ErrorCode.AUTH_REQUIRED, "authentication required", false),
                requestId,
                traceId
            )));
    }

    public record LoginRequest(String username, String password) {
    }
}
