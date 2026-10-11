package com.ts.ticketing.security;

import java.io.IOException;
import java.time.Clock;

import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ts.ticketing.api.ErrorResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security: who may call what (FR-01, FR-02, NFR-10). Token issuance, health and
 * metrics are public; creating a show needs the admin role; everything else needs a valid token.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt, ObjectMapper json)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)          // no cookies or sessions, so no CSRF surface
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/health/live", "/health/ready", "/metrics").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/shows").hasRole(Role.ADMIN.name())
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, json, HttpStatus.UNAUTHORIZED,
                                ErrorResponse.of("unauthorized", "missing, expired or invalid token")))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, json, HttpStatus.FORBIDDEN,
                                ErrorResponse.of("forbidden", "admin role required"))))
                .addFilterBefore(new JwtAuthenticationFilter(jwt), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public JwtService jwtService(JwtProperties props, Clock clock) {
        return new JwtService(props, clock);
    }

    /** Replaces Boot's default in-memory user, so no generated password is printed at startup. */
    @Bean
    public UserDetailsService noUsers() {
        return new InMemoryUserDetailsManager();
    }

    private static void writeError(HttpServletResponse res, ObjectMapper json, HttpStatus status, ErrorResponse body)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getOutputStream(), body);
    }
}
