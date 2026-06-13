package com.meeting.api;

import com.meeting.api.start.MeetingApiApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import jakarta.servlet.http.Cookie;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = MeetingApiApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthRefreshFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg15")
        .withDatabaseName("meeting_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeAll
    static void checkDocker() {
        TestcontainersDockerPreflight.assumeDockerAvailable();
    }

    @Autowired MockMvc mockMvc;

    @Test
    void fullFlow_loginRefreshLogout() throws Exception {
        // 1. Login
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
            .contentType("application/json")
            .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie("REFRESH_TOKEN");
        Cookie csrfCookie = loginResult.getResponse().getCookie("XSRF-TOKEN");

        // 2. Refresh
        mockMvc.perform(post("/api/auth/refresh")
            .cookie(refreshCookie)
            .cookie(csrfCookie)
            .header("X-CSRF-Token", csrfCookie.getValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        // 3. Logout
        mockMvc.perform(post("/api/auth/logout")
            .cookie(refreshCookie))
            .andExpect(status().isOk());

        // 4. Refresh should fail after logout
        mockMvc.perform(post("/api/auth/refresh")
            .cookie(refreshCookie)
            .cookie(csrfCookie)
            .header("X-CSRF-Token", csrfCookie.getValue()))
            .andExpect(status().isUnauthorized());
    }
}
