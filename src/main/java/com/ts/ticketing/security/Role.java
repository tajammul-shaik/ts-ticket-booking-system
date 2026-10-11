package com.ts.ticketing.security;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Role {
    USER,
    ADMIN;

    /** Value in the JWT "role" claim and in JSON: "user" / "admin". */
    @JsonValue
    public String claim() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Spring Security authority, matched by {@code hasRole("ADMIN")}. */
    public String authority() {
        return "ROLE_" + name();
    }

    public static Role fromClaim(String claim) {
        for (Role role : values()) {
            if (role.claim().equals(claim)) {
                return role;
            }
        }
        throw new IllegalArgumentException("unknown role: " + claim);
    }
}
