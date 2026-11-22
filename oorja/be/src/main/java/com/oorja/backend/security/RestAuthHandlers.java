package com.oorja.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oorja.backend.dto.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Filter-level security rejections, in the same {@link ApiErrorResponse} envelope as every other error.
 * <p>
 * Why this exists: with no entry point configured, Spring Security answers an unauthenticated request
 * with a bare <b>403</b>. The frontend only refreshes an expired access token on a <b>401</b>
 * (see fe/src/lib/api.ts), so without this an expired token would be reported as "forbidden" and the
 * silent refresh would never run. The contract is therefore:
 * <ul>
 *   <li>401 — no / expired / invalid token (client should refresh, then re-login);</li>
 *   <li>403 — authenticated, but the role may not access this route (client must NOT log out).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RestAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Please log in to continue.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "You don't have permission to do that.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                ApiErrorResponse.of(status.value(), code, message, null, request.getRequestURI()));
    }
}
