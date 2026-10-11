package com.ts.ticketing.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.ts.ticketing.security.JwtService;
import com.ts.ticketing.security.Role;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * FR-01: demo token issuance. No passwords (real identity is out of scope); the only privileged
 * path is the admin secret, and a wrong secret silently yields a user token. The token itself is
 * never logged (NFR-10).
 */
@RestController
public class AuthController {

    static final String ADMIN_SECRET_HEADER = "X-Admin-Secret";

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final JwtService jwt;
    private final byte[] adminSecret;

    public AuthController(JwtService jwt, IdentityProperties props) {
        this.jwt = jwt;
        this.adminSecret = props.adminSecret().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/auth/token")
    public TokenResponse issueToken(
            @Valid @RequestBody TokenRequest request,
            @RequestHeader(name = ADMIN_SECRET_HEADER, required = false) String presentedSecret) {
        Role role = isAdminSecret(presentedSecret) ? Role.ADMIN : Role.USER;
        JwtService.IssuedToken issued = jwt.issue(request.userId(), role);
        log.debug("token issued {} {}", kv("user_id", request.userId()), kv("role", role.claim()));
        return new TokenResponse(issued.token(), request.userId(), role, issued.expiresAt().getEpochSecond());
    }

    /** Constant-time comparison, so response timing doesn't leak how much of the secret matched. */
    private boolean isAdminSecret(String presented) {
        return presented != null
                && MessageDigest.isEqual(adminSecret, presented.getBytes(StandardCharsets.UTF_8));
    }

    /** user_id fits reservations.user_id VARCHAR(64); the charset keeps ids safe to log. */
    public record TokenRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9_.@-]+", message = "may contain only letters, digits and _ . @ -")
            String userId) {
    }

    /** @param expiresAt epoch seconds */
    public record TokenResponse(String token, String userId, Role role, long expiresAt) {
    }
}
