package com.smms.security;

import com.smms.sys.entity.SysTokenBlacklist;
import com.smms.sys.mapper.SysTokenBlacklistMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 认证过滤器（IR-SW-03）：
 * 解析 Bearer Token → 黑名单校验 → 加载用户上下文（含权限版本比对）→ 写入 SecurityContext。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final UserContextService userContextService;
    private final SysTokenBlacklistMapper blacklistMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                Claims claims = jwtTokenService.parse(header.substring(7));
                if (claims != null && claims.getId() != null) {
                    authenticate(claims);
                }
            }
            chain.doFilter(request, response);
        } finally {
            IssuedTokenHolder.clear();
        }
    }

    private void authenticate(Claims claims) {
        // 1. 黑名单校验（登出/密码修改后 Token 即时失效）
        if (blacklistMapper.selectById(claims.getId()) != null) {
            return;
        }
        Long userId = claims.get("uid", Long.class);
        if (userId == null) {
            return;
        }
        // 2. 加载用户上下文（Token 版本号与当前权限版本不一致时强制重建）
        Long tokenVersion = claims.get("ver", Long.class);
        LoginUser user = userContextService.getLoginUser(userId, tokenVersion);
        if (user == null) {
            return; // 用户已停用/删除
        }
        // 3. 密码指纹比对：密码修改后其他端 Token 立即失效（FR-SYS-03）
        String pwdFp = claims.get("pwdFp", String.class);
        if (pwdFp == null || !pwdFp.equals(user.pwdFp())) {
            return;
        }
        // 4. 写入认证上下文：角色（ROLE_ 前缀）+ 权限码
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        user.roleCodes().forEach(code -> authorities.add(new SimpleGrantedAuthority("ROLE_" + code)));
        user.permCodes().forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        // 5. 记录当前 Token 信息（登出/改密时写入黑名单）
        IssuedTokenHolder.set(claims.getId(), java.time.LocalDateTime.ofInstant(
                claims.getExpiration().toInstant(), java.time.ZoneId.systemDefault()));
    }
}
