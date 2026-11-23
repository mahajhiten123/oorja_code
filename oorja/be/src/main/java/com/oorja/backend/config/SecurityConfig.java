package com.oorja.backend.config;

import com.oorja.backend.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final com.oorja.backend.security.RestAuthHandlers restAuthHandlers;

    /**
     * Comma-separated list of allowed origins, e.g.
     * "https://app.oorjaonmove.com,https://admin.oorjaonmove.com". Because
     * allowCredentials(true) is set below, this must never contain "*" -
     * that combination lets ANY origin send authenticated, credentialed
     * requests, which is a real CORS hole. See corsConfigurationSource().
     */
    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 401 for "no/expired token" (frontend refreshes on this), 403 for "wrong role" - both as JSON.
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint(restAuthHandlers)
                .accessDeniedHandler(restAuthHandlers))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/actuator/health", "/api/customer/services", "/api/admin/auth/**", "/api/customer/auth/**", "/api/webhooks/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/customer/bookings/slots").permitAll()
                .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/customer/**").hasRole("CUSTOMER")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = List.of(allowedOrigins.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        if (origins.isEmpty()) {
            throw new IllegalStateException(
                    "app.cors.allowed-origins (env: CORS_ALLOWED_ORIGINS) is not set. Refusing to start with an " +
                    "open CORS policy - set it to your actual frontend origin(s), e.g. " +
                    "https://app.oorjaonmove.com. For local dev use http://localhost:5173.");
        }
        if (origins.contains("*")) {
            throw new IllegalStateException(
                    "app.cors.allowed-origins (env: CORS_ALLOWED_ORIGINS) must not be \"*\" - combined with " +
                    "allowCredentials(true) this allows any website to make authenticated requests on behalf of " +
                    "logged-in users. List the exact origin(s) instead.");
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
