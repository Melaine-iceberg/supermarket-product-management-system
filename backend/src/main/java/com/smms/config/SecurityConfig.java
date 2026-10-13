package com.smms.config;

import com.smms.common.api.ApiResponse;
import com.smms.common.api.ErrorCode;
import com.smms.security.JwtAuthFilter;
import com.smms.security.MustChangePwdFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Security 配置（IR-SW-03、NFR-SEC-01/02）：
 * 无状态 JWT；登录与监控端点放行；其余接口须认证；
 * 401/403 返回统一 JSON 结构；CORS 允许前端开发域。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final MustChangePwdFilter mustChangePwdFilter;
    private final ObjectMapper objectMapper;

    @Value("${smms.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/store-name").permitAll()
                        .requestMatchers("/api/v1/ping").permitAll()
                        // 健康检查探针匿名放行（NFR-SEC-01 例外、IR-SW-08：容器编排/运维脚本使用）；
                        // 管理端点 /actuator/info 须鉴权，不再匿名（GAP-P2-27）
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> writeJson(
                                response, HttpStatus.UNAUTHORIZED, ApiResponse.fail(ErrorCode.UNAUTHORIZED)))
                        .accessDeniedHandler((request, response, accessDeniedException) -> writeJson(
                                response, HttpStatus.FORBIDDEN, ApiResponse.fail(ErrorCode.FORBIDDEN))))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(mustChangePwdFilter, JwtAuthFilter.class);
        return http.build();
    }

    /** BCrypt 强度 10（BR-SYS-02） */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 经 Nginx 反代的容器化部署中，浏览器同源请求同样携带 Origin，
        // 且部署端口可配置——放行本机任意端口来源，非本机来源须显式配置（SMMS_CORS_ORIGINS）。
        // SMMS_CORS_ORIGINS 中允许使用 "*" 表示放行任意来源（多设备/多网络/多域名免维护场景，
        // 因 allowCredentials=true，"*" 只能经 allowedOriginPatterns 表达，Spring 会回显请求 Origin）。
        List<String> patterns = new ArrayList<>(List.of("http://localhost:[*]", "http://127.0.0.1:[*]"));
        List<String> origins = new ArrayList<>();
        for (String item : allowedOrigins.split(",")) {
            String origin = item.trim();
            if (origin.isEmpty()) {
                continue;
            }
            if ("*".equals(origin)) {
                patterns.add("*");
            } else {
                origins.add(origin);
            }
        }
        config.setAllowedOrigins(origins);
        config.setAllowedOriginPatterns(patterns);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private void writeJson(jakarta.servlet.http.HttpServletResponse response,
                           HttpStatus status, ApiResponse<?> body) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(objectMapper.writeValueAsBytes(body));
        response.getOutputStream().flush();
    }
}
