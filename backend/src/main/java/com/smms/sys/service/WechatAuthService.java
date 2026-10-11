package com.smms.sys.service;

import tools.jackson.databind.JsonNode;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.sys.config.LoginProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信网页授权登录（V1.2）。
 *
 * <p>流程：前端跳转 {@link #buildAuthUrl()} → 用户扫码 → 微信带着 {@code code}/{@code state}
 * 回调前端页面 → 前端把两者回传 {@code POST /auth/wechat/login} → 后端换 openid 并签发本系统 Token。</p>
 *
 * <p>state 用于防 CSRF：后端下发时缓存，回调时必须命中且未过期、且只能用一次。
 * 当前实现为单机内存缓存；若后端多实例部署，需把 STATE_STORE 换成 Redis（接口已隔离在
 * {@link #consumeState(String)} 内，替换不影响调用方）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatAuthService {

    private static final String AUTHORIZE_URL = "https://open.weixin.qq.com/connect/qrconnect";
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    private static final String USER_INFO_URL = "https://api.weixin.qq.com/sns/userinfo";

    /** state 有效期（分钟） */
    private static final int STATE_TTL_MINUTES = 10;

    private static final Map<String, LocalDateTime> STATE_STORE = new ConcurrentHashMap<>();

    private final LoginProperties loginProperties;

    /**
     * 微信返回的用户标识。
     *
     * @param openid   应用内唯一
     * @param unionid  开放平台账号下唯一，未绑定开放平台时可能为空
     * @param nickname 昵称
     * @param avatar   头像地址
     */
    public record WechatUser(String openid, String unionid, String nickname, String avatar) {
    }

    /**
     * 扫码地址与配套 state。二者必须成对使用，state 回传后由 {@link #consumeState(String)} 校验。
     */
    public record AuthUrl(String url, String state) {
    }

    /** 微信登录是否已配置齐全（前端据此决定是否显示微信登录按钮） */
    public boolean ready() {
        return loginProperties.wechatReady();
    }

    /**
     * 生成带一次性 state 的微信扫码地址。
     */
    public AuthUrl buildAuthUrl() {
        if (!ready()) {
            throw new BusinessException(ErrorCode.WECHAT_NOT_CONFIGURED);
        }
        String state = UUID.randomUUID().toString().replace("-", "");
        expireStates();
        STATE_STORE.put(state, LocalDateTime.now().plusMinutes(STATE_TTL_MINUTES));

        LoginProperties.Wechat cfg = loginProperties.getWechat();
        String url = AUTHORIZE_URL
                + "?appid=" + enc(cfg.getAppId())
                + "&redirect_uri=" + enc(cfg.getRedirectUri() == null ? "" : cfg.getRedirectUri())
                + "&response_type=code"
                + "&scope=" + enc(cfg.getScope())
                + "&state=" + state
                + "#wechat_redirect";
        return new AuthUrl(url, state);
    }

    /**
     * 校验 state 并消费掉（一次性）。
     */
    public void consumeState(String state) {
        if (state == null || state.isBlank()) {
            throw new BusinessException(ErrorCode.WECHAT_STATE_INVALID);
        }
        LocalDateTime expireAt = STATE_STORE.remove(state);
        if (expireAt == null || expireAt.isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.WECHAT_STATE_INVALID);
        }
    }

    /**
     * 用授权 code 换取微信用户信息。
     */
    public WechatUser exchange(String code) {
        LoginProperties.Wechat cfg = loginProperties.getWechat();
        RestClient client = buildClient();

        JsonNode tokenResp = client.get()
                .uri(ACCESS_TOKEN_URL + "?appid={appid}&secret={secret}&code={code}&grant_type=authorization_code",
                        cfg.getAppId(), cfg.getAppSecret(), code)
                .retrieve()
                .body(JsonNode.class);

        if (tokenResp == null || tokenResp.has("errcode")) {
            log.warn("微信 code 换 token 失败: {}", tokenResp == null ? "空响应" : tokenResp.toString());
            throw new BusinessException(ErrorCode.WECHAT_AUTH_FAILED);
        }
        String openid = tokenResp.path("openid").asText(null);
        String unionid = tokenResp.path("unionid").asText(null);
        String accessToken = tokenResp.path("access_token").asText(null);
        if (openid == null) {
            throw new BusinessException(ErrorCode.WECHAT_AUTH_FAILED);
        }

        String nickname = null;
        String avatar = null;
        if (accessToken != null) {
            try {
                JsonNode infoResp = client.get()
                        .uri(USER_INFO_URL + "?access_token={token}&openid={openid}&lang=zh_CN", accessToken, openid)
                        .retrieve()
                        .body(JsonNode.class);
                if (infoResp != null && !infoResp.has("errcode")) {
                    nickname = infoResp.path("nickname").asText(null);
                    avatar = infoResp.path("headimgurl").asText(null);
                }
            } catch (RuntimeException ex) {
                // 昵称头像属锦上添花，拉取失败不影响登录
                log.warn("拉取微信用户资料失败，仅使用 openid 登录: {}", ex.getMessage());
            }
        }
        return new WechatUser(openid, unionid, nickname, avatar);
    }

    private RestClient buildClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }

    private void expireStates() {
        LocalDateTime now = LocalDateTime.now();
        STATE_STORE.entrySet().removeIf(e -> e.getValue().isBefore(now));
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
