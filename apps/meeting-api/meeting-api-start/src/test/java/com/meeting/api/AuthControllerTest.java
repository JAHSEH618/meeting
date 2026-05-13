package com.meeting.api;

import com.meeting.api.adapter.auth.AuthController;
import com.meeting.api.app.auth.InMemoryAuthApplicationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest {

    @Test
    void loginReturnsAccessTokenAndMeResolvesUser() {
        InMemoryAuthApplicationService auth = new InMemoryAuthApplicationService();
        AuthController controller = new AuthController(auth);

        var login = controller.login("req_01", "trace_01", new AuthController.LoginRequest("admin", "admin123"));

        assertThat(login.success()).isTrue();
        assertThat(login.data().accessToken()).startsWith("mvp0_");

        var me = controller.me("Bearer " + login.data().accessToken(), "req_02", "trace_02");
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody().data().tenantId()).isEqualTo("tenant_default");
    }
}
