package com.meeting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.adapter.auth.AuthController;
import com.meeting.api.app.auth.InMemoryAuthApplicationService;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void loginReturnsAccessTokenAndMeResolvesUser() {
        InMemoryAuthApplicationService auth = new InMemoryAuthApplicationService();
        AuthController controller = new AuthController(auth);
        MockHttpServletResponse response = new MockHttpServletResponse();

        var login = controller.login("req_01", "trace_01", new AuthController.LoginRequest("admin", "admin123"), response);

        assertThat(login.getBody().success()).isTrue();
        String token = login.getBody().data().accessToken();
        assertThat(token.split("\\.")).hasSize(3);

        // Verify cookies are set
        Cookie refreshCookie = response.getCookie("REFRESH_TOKEN");
        assertThat(refreshCookie).isNotNull();
        assertThat(refreshCookie.isHttpOnly()).isTrue();
        assertThat(refreshCookie.getSecure()).isTrue();
        assertThat(refreshCookie.getPath()).isEqualTo("/api/auth");

        Cookie csrfCookie = response.getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.getSecure()).isTrue();
        assertThat(csrfCookie.getPath()).isEqualTo("/api");

        JsonNode payload = jwtPayload(token);
        assertThat(payload.path("sub").asText()).isEqualTo("user_admin");
        assertThat(payload.path("tenantId").asText()).isEqualTo("tenant_default");
        assertThat(payload.path("aud").asText()).isEqualTo("ai-worker-admin");
        assertThat(payload.path("iss").asText()).isEqualTo("meeting-api");
        assertThat(payload.path("roles").toString()).contains("\"ADMIN\"");

        var me = controller.me("Bearer " + token, "req_02", "trace_02");
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody().data().tenantId()).isEqualTo("tenant_default");

        String tampered = token.substring(0, token.length() - 2) + "xx";
        var rejected = controller.me("Bearer " + tampered, "req_03", "trace_03");
        assertThat(rejected.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void refresh_returnsNewAccessToken() {
        InMemoryAuthApplicationService auth = new InMemoryAuthApplicationService();
        AuthController controller = new AuthController(auth);

        // First login to get refresh token
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        var login = controller.login("req_01", "trace_01", new AuthController.LoginRequest("admin", "admin123"), loginResponse);
        assertThat(login.getBody().success()).isTrue();

        String refreshTokenId = loginResponse.getCookie("REFRESH_TOKEN").getValue();
        String csrfToken = loginResponse.getCookie("XSRF-TOKEN").getValue();

        // Now refresh
        MockHttpServletResponse refreshResponse = new MockHttpServletResponse();
        var refresh = controller.refresh(refreshTokenId, csrfToken, csrfToken, "req_02", "trace_02", refreshResponse);

        assertThat(refresh.getStatusCode().value()).isEqualTo(200);
        assertThat(refresh.getBody().success()).isTrue();
        assertThat(refresh.getBody().data().accessToken()).isNotNull();

        // Verify CSRF token was rotated
        Cookie newCsrfCookie = refreshResponse.getCookie("XSRF-TOKEN");
        assertThat(newCsrfCookie).isNotNull();
        assertThat(newCsrfCookie.getValue()).isNotEqualTo(csrfToken);
    }

    @Test
    void refresh_rejects_missingCSRF() {
        InMemoryAuthApplicationService auth = new InMemoryAuthApplicationService();
        AuthController controller = new AuthController(auth);
        MockHttpServletResponse response = new MockHttpServletResponse();

        var refresh = controller.refresh("rt_123", null, "csrf_abc", "req_01", "trace_01", response);

        assertThat(refresh.getStatusCode().value()).isEqualTo(401);
        assertThat(refresh.getBody().error().code().name()).isEqualTo("CSRF_TOKEN_INVALID");
    }

    @Test
    void refresh_rejects_mismatchedCSRF() {
        InMemoryAuthApplicationService auth = new InMemoryAuthApplicationService();
        AuthController controller = new AuthController(auth);
        MockHttpServletResponse response = new MockHttpServletResponse();

        var refresh = controller.refresh("rt_123", "csrf_header", "csrf_cookie", "req_01", "trace_01", response);

        assertThat(refresh.getStatusCode().value()).isEqualTo(401);
        assertThat(refresh.getBody().error().code().name()).isEqualTo("CSRF_TOKEN_INVALID");
    }

    private static JsonNode jwtPayload(String token) {
        try {
            String payload = token.split("\\.")[1];
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            return MAPPER.readTree(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("invalid JWT payload", e);
        }
    }
}
