package com.smms.security;

import com.smms.common.api.ErrorCode;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 强制修改初始密码过滤器单元测试（GAP-P0-08，BR-SYS-02）：
 * mustChangePwd=1 时除改密/登出/me 等白名单外的业务接口一律 403（错误码 1114），
 * 仅靠前端路由守卫无法再用旧 Token 直调 API 绕过。
 */
class MustChangePwdFilterTest {

    private MustChangePwdFilter filter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        filter = new MustChangePwdFilter(objectMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private LoginUser loginUser(boolean mustChangePwd) {
        return new LoginUser(5L, "cashier", "陈收银", List.of("CASHIER"), List.of("收银员"),
                Set.of("sys:cashier:enter"), "fp", mustChangePwd);
    }

    private void authenticate(LoginUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Test
    @DisplayName("mustChangePwd=1：业务接口被拒（403 + 1114），请求不进入后续链路")
    void blocksBusinessApiWhenMustChangePwd() throws ServletException, IOException {
        authenticate(loginUser(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/members");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(ErrorCode.MUST_CHANGE_PWD.getCode());
        assertThat(chain.getRequest()).isNull(); // 未放行
    }

    @Test
    @DisplayName("mustChangePwd=1：改密（PUT /auth/password）、登出（POST /auth/logout）、me 放行")
    void allowsPasswordChangeLogoutAndMe() throws ServletException, IOException {
        authenticate(loginUser(true));
        for (String[] target : new String[][]{
                {"PUT", "/api/v1/auth/password"},
                {"POST", "/api/v1/auth/logout"},
                {"GET", "/api/v1/auth/me"}}) {
            MockHttpServletRequest request = new MockHttpServletRequest(target[0], target[1]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(chain.getRequest()).as(target[1]).isNotNull();
        }
    }

    @Test
    @DisplayName("mustChangePwd=0：业务接口正常放行")
    void allowsBusinessApiWhenPasswordChanged() throws ServletException, IOException {
        authenticate(loginUser(false));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/members");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("未认证请求：过滤器不处理（交由认证链返回 401）")
    void skipsUnauthenticatedRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/members");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }
}
