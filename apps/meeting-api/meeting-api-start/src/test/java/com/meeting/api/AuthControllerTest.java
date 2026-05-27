package com.meeting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.adapter.auth.AuthController;
import com.meeting.api.app.auth.InMemoryAuthApplicationService;
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

        var login = controller.login("req_01", "trace_01", new AuthController.LoginRequest("admin", "admin123"));

        assertThat(login.success()).isTrue();
        String token = login.data().accessToken();
        assertThat(token.split("\\.")).hasSize(3);

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
