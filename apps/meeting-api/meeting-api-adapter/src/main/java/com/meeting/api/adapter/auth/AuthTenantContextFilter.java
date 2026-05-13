package com.meeting.api.adapter.auth;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.auth.AuthFacade;
import com.meeting.api.client.auth.AuthUserDTO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthTenantContextFilter extends OncePerRequestFilter {
    private final AuthFacade authFacade;

    public AuthTenantContextFilter(AuthFacade authFacade) {
        this.authFacade = authFacade;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        try {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && !authorization.isBlank()) {
                authFacade.authenticate(authorization)
                    .ifPresent(user -> setContext(user, request));
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void setContext(AuthUserDTO user, HttpServletRequest request) {
        TenantContextHolder.set(
            user.tenantId(),
            user.userId(),
            request.getHeader("X-Request-Id")
        );
    }
}
