package com.meeting.api.adapter.auth;

import com.meeting.api.client.auth.AuthFacade;
import com.meeting.api.client.auth.AuthUserDTO;
import com.meeting.api.client.auth.LoginCommand;
import com.meeting.api.client.auth.LoginResultDTO;
import com.meeting.api.client.common.ApiResponse;
import org.springframework.http.ResponseEntity;
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
    public ApiResponse<LoginResultDTO> login(
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
        @RequestBody LoginRequest request
    ) {
        return ApiResponse.ok(
            authFacade.login(new LoginCommand(request.username(), request.password(), requestId, traceId)),
            requestId,
            traceId
        );
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
        @RequestHeader("Authorization") String authorization,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId
    ) {
        authFacade.logout(authorization);
        return ApiResponse.ok(null, requestId, traceId);
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthUserDTO>> me(
        @RequestHeader("Authorization") String authorization,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId
    ) {
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
