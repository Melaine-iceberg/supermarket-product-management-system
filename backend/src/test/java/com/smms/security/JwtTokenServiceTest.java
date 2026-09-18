package com.smms.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JWT 签发/解析测试（IR-SW-03）。
 */
class JwtTokenServiceTest {

    private static final String SECRET = "unit-test-secret-key-with-at-least-32-bytes!!";

    private JwtTokenService service() {
        return new JwtTokenService(SECRET, new GlobalPermVersion());
    }

    private LoginUser user() {
        return new LoginUser(7L, "admin", "系统管理员", List.of("SUPER_ADMIN"), List.of("超级管理员"),
                Set.of("sys:user:list"), "abc123def4567890", false);
    }

    @Test
    void shouldRoundTripClaims() {
        JwtTokenService.IssuedToken issued = service().createToken(user(), 8);
        Claims claims = service().parse(issued.token());
        assertNotNull(claims);
        assertEquals("admin", claims.getSubject());
        assertEquals(7L, claims.get("uid", Long.class));
        assertEquals("SUPER_ADMIN", claims.get("roles", String.class));
        assertEquals("abc123def4567890", claims.get("pwdFp", String.class));
        assertNotNull(claims.getId());
    }

    @Test
    void shouldRejectTamperedToken() {
        JwtTokenService.IssuedToken issued = service().createToken(user(), 8);
        String tampered = issued.token().substring(0, issued.token().length() - 3) + "abc";
        assertNull(service().parse(tampered));
    }

    @Test
    void shouldRejectForeignKeyToken() {
        String foreign = service().createToken(user(), 8).token();
        JwtTokenService other = new JwtTokenService(
                "another-secret-key-also-with-32-bytes-minimum!", new GlobalPermVersion());
        assertNull(other.parse(foreign));
    }

    @Test
    void shouldRejectExpiredToken() {
        JwtTokenService.IssuedToken issued = service().createToken(user(), -1);
        assertNull(service().parse(issued.token()));
    }

    @Test
    void shouldRejectShortSecret() {
        assertThrows(IllegalStateException.class,
                () -> new JwtTokenService("too-short", new GlobalPermVersion()));
    }
}
