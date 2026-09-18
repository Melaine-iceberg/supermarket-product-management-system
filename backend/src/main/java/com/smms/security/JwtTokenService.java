package com.smms.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析（IR-SW-03）：
 * HS256，密钥配置注入且长度 ≥ 32 字节；Token 置于 Authorization: Bearer 头；
 * 载荷含用户 ID、用户名、角色代码列表、权限版本号、过期时间与 jti。
 */
@Slf4j
@Service
public class JwtTokenService {

    public record IssuedToken(String token, String jti, Instant expireAt) {
    }

    private final SecretKey key;
    private final GlobalPermVersion permVersion;

    public JwtTokenService(@Value("${smms.jwt.secret}") String secret,
                           GlobalPermVersion permVersion) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT 密钥长度不足 32 字节（IR-SW-03）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.permVersion = permVersion;
    }

    /** 签发 Token；expireHours 由调用方从系统参数 token_expire_hours 读取（FR-SYS-01） */
    public IssuedToken createToken(LoginUser user, long expireHours) {
        Instant now = Instant.now();
        Instant expireAt = now.plusSeconds(expireHours * 3600);
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(user.username())
                .id(jti)
                .claim("uid", user.userId())
                .claim("roles", String.join(",", user.roleCodes()))
                .claim("pwdFp", user.pwdFp())
                .claim("ver", permVersion.current())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expireAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, jti, expireAt);
    }

    /** 解析并校验 Token，失败返回 null（过期/签名错误/格式错误） */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }
}
