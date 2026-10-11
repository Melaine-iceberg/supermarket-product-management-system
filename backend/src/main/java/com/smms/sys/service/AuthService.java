package com.smms.sys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.common.util.PasswordPolicy;
import com.smms.common.util.RequestUtils;
import com.smms.common.util.UserAgentUtils;
import com.smms.security.IssuedTokenHolder;
import com.smms.security.JwtTokenService;
import com.smms.security.LoginUser;
import com.smms.security.LoginRateLimiter;
import com.smms.security.SecurityUtils;
import com.smms.security.UserContextService;
import com.smms.sys.config.LoginProperties;
import com.smms.sys.dto.CodeLoginRequest;
import com.smms.sys.dto.LoginMethodsVO;
import com.smms.sys.dto.LoginRequest;
import com.smms.sys.dto.LoginResponse;
import com.smms.sys.dto.MeResponse;
import com.smms.sys.dto.MenuNode;
import com.smms.sys.dto.RegisterRequest;
import com.smms.sys.dto.SendCodeRequest;
import com.smms.sys.dto.UserInfo;
import com.smms.sys.dto.WechatLoginRequest;
import com.smms.sys.entity.LoginLog;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.entity.SysAuthCode;
import com.smms.sys.entity.SysTokenBlacklist;
import com.smms.sys.entity.SysUser;
import com.smms.sys.entity.SysUserRole;
import com.smms.sys.mapper.SysRoleMapper;
import com.smms.sys.mapper.SysTokenBlacklistMapper;
import com.smms.sys.mapper.SysUserMapper;
import com.smms.sys.mapper.SysUserRoleMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证服务（FR-SYS-01、FR-SYS-03）：
 * 登录（锁定策略/记住我/登录日志）、登出（黑名单）、当前用户、修改自身密码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 记住我：Token 有效期延长到 72 小时（FR-SYS-01） */
    private static final long REMEMBER_ME_HOURS = 72;

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysTokenBlacklistMapper blacklistMapper;
    private final JwtTokenService jwtTokenService;
    private final UserContextService userContextService;
    private final SysConfigService configService;
    private final SysPermissionService permissionService;
    private final LoginLogService loginLogService;
    private final OperationLogService operationLogService;
    private final LoginRateLimiter loginRateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final AuthCodeService authCodeService;
    private final WechatAuthService wechatAuthService;
    private final LoginProperties loginProperties;

    public LoginResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String ip = RequestUtils.getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        // 登录频率限制（NFR-SEC-03：同 IP 每分钟 ≤ 20 次）
        if (!loginRateLimiter.tryAcquire(ip)) {
            loginLog(httpRequest, request.username(), null, 0, "尝试过于频繁", ip, userAgent);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "登录尝试过于频繁，请 1 分钟后重试");
        }

        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, request.username()));

        // 统一模糊提示，避免暴露账号是否存在（NFR-SEC-01）
        if (user == null) {
            loginLog(httpRequest, request.username(), null, 0, "用户名或密码错误", ip, userAgent);
            throw new BusinessException(ErrorCode.USERNAME_PASSWORD_ERROR);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            loginLog(httpRequest, request.username(), user.getId(), 0, "账号已停用", ip, userAgent);
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        ensureNotLocked(user, request.username(), ip, userAgent, httpRequest);

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            handlePasswordFail(user, request.username(), ip, userAgent);
            throw new BusinessException(ErrorCode.USERNAME_PASSWORD_ERROR);
        }

        return finishLogin(user, Boolean.TRUE.equals(request.rememberMe()), ip, userAgent, httpRequest, "登录成功");
    }

    /**
     * 手机号/邮箱 + 验证码登录（V1.2）。
     *
     * <p>安全取舍：这里不复用「用户名或密码错误」的模糊提示——验证码已由用户持有的手机/邮箱证明身份，
     * 此时提示「未绑定账号」不会泄露额外信息，反而能避免用户反复重试。</p>
     */
    public LoginResponse loginByCode(CodeLoginRequest request, HttpServletRequest httpRequest) {
        String ip = RequestUtils.getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        if (!loginRateLimiter.tryAcquire(ip)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "登录尝试过于频繁，请 1 分钟后重试");
        }

        boolean byPhone = CodeLoginRequest.TYPE_PHONE.equals(request.loginType());
        String channel = byPhone ? SysAuthCode.CHANNEL_SMS : SysAuthCode.CHANNEL_EMAIL;
        String target = byPhone ? trimOrNull(request.phone()) : trimOrNull(request.email());
        if (target == null) {
            throw new BusinessException(byPhone ? ErrorCode.PHONE_INVALID : ErrorCode.EMAIL_INVALID);
        }
        ensureCodeLoginEnabled(byPhone);

        SysUser user = byPhone ? findByPhone(target) : findByEmail(target);
        if (user == null) {
            loginLog(httpRequest, target, null, 0, "登录凭证未绑定账号", ip, userAgent);
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_BOUND);
        }
        ensureAccountEnabled(user, target, ip, userAgent, httpRequest);

        authCodeService.verify(channel, target, SysAuthCode.SCENE_LOGIN, request.code());

        return finishLogin(user, Boolean.TRUE.equals(request.rememberMe()), ip, userAgent, httpRequest, "验证码登录成功");
    }

    /**
     * 微信扫码登录（V1.2）。
     *
     * <p>微信只负责证明「你是谁」，账号必须是系统里已存在的：未绑定时提示用户先用密码登录再绑定，
     * 避免任何人扫码即获得后台权限。</p>
     */
    public LoginResponse loginByWechat(WechatLoginRequest request, HttpServletRequest httpRequest) {
        String ip = RequestUtils.getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        if (!loginProperties.wechatReady()) {
            throw new BusinessException(ErrorCode.WECHAT_NOT_CONFIGURED);
        }
        if (!loginRateLimiter.tryAcquire(ip)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "登录尝试过于频繁，请 1 分钟后重试");
        }

        wechatAuthService.consumeState(request.state());
        WechatAuthService.WechatUser wechatUser = wechatAuthService.exchange(request.code());

        SysUser user = findByWechat(wechatUser);
        if (user == null) {
            loginLog(httpRequest, "wechat:" + wechatUser.openid(), null, 0, "微信未绑定账号", ip, userAgent);
            throw new BusinessException(ErrorCode.WECHAT_NOT_BOUND);
        }
        ensureAccountEnabled(user, user.getUsername(), ip, userAgent, httpRequest);

        return finishLogin(user, false, ip, userAgent, httpRequest, "微信登录成功");
    }

    /**
     * 员工自助注册（V1.2）。
     *
     * <p>注册即用：校验验证码后直接建号、绑定默认角色并自动登录。默认角色由
     * {@code smms.login.default-role-id} 指定（默认收银员），不含系统管理权限。</p>
     */
    @Transactional
    public LoginResponse register(RegisterRequest request, HttpServletRequest httpRequest) {
        if (!loginProperties.isRegister()) {
            throw new BusinessException(ErrorCode.REGISTER_DISABLED);
        }
        String ip = RequestUtils.getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        if (!loginRateLimiter.tryAcquire(ip)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "操作过于频繁，请 1 分钟后重试");
        }

        String phone = trimOrNull(request.phone());
        String email = trimOrNull(request.email());
        if (phone == null && email == null) {
            throw new BusinessException(ErrorCode.PHONE_INVALID, "手机号与邮箱至少填写一项");
        }

        if (userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, request.username())) > 0) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }
        if (phone != null && findByPhone(phone) != null) {
            throw new BusinessException(ErrorCode.PHONE_EXISTS);
        }
        if (email != null && findByEmail(email) != null) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS);
        }

        String policyError = PasswordPolicy.validate(request.username(), request.password());
        if (policyError != null) {
            throw new BusinessException(ErrorCode.WEAK_PASSWORD, policyError);
        }

        // 验证码是唯一的身份凭证，必须在写库前校验
        if (phone != null) {
            authCodeService.verify(SysAuthCode.CHANNEL_SMS, phone, SysAuthCode.SCENE_REGISTER, request.code());
        } else {
            authCodeService.verify(SysAuthCode.CHANNEL_EMAIL, email, SysAuthCode.SCENE_REGISTER, request.code());
        }

        LocalDateTime now = LocalDateTime.now();
        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRealName(trimOrNull(request.realName()) != null ? request.realName().trim() : request.username());
        user.setPhone(phone);
        user.setEmail(email);
        user.setStatus(1);
        user.setFailCount(0);
        user.setMustChangePwd(0);
        user.setRemark("自助注册（V1.2）");
        user.setCreateBy(request.username());
        user.setCreateTime(now);
        user.setUpdateBy(request.username());
        user.setUpdateTime(now);
        userMapper.insert(user);

        Long roleId = loginProperties.getDefaultRoleId();
        if (roleId != null) {
            SysUserRole relation = new SysUserRole();
            relation.setUserId(user.getId());
            relation.setRoleId(roleId);
            userRoleMapper.insert(relation);
        } else {
            log.warn("未配置 smms.login.default-role-id，自助注册用户 {} 暂无任何角色", user.getUsername());
        }

        operationLog(user.getUsername(), "", "系统管理", "自助注册", user.getUsername(), ip, userAgent);
        return finishLogin(user, false, ip, userAgent, httpRequest, "注册成功");
    }

    /**
     * 发送登录/注册验证码（V1.2）。
     *
     * <p>发送前先判定场景是否成立（登录场景要求账号已绑定、注册场景要求尚未占用），
     * 避免用户拿到验证码后才发现走不通。</p>
     */
    public Map<String, Object> sendCode(SendCodeRequest request, HttpServletRequest httpRequest) {
        String ip = RequestUtils.getClientIp(httpRequest);
        boolean byPhone = SendCodeRequest.CHANNEL_SMS.equals(request.channel());
        String target = byPhone ? trimOrNull(request.phone()) : trimOrNull(request.email());
        if (target == null) {
            throw new BusinessException(byPhone ? ErrorCode.PHONE_INVALID : ErrorCode.EMAIL_INVALID);
        }
        ensureChannelEnabled(byPhone);

        if (SendCodeRequest.SCENE_LOGIN.equals(request.scene())) {
            SysUser user = byPhone ? findByPhone(target) : findByEmail(target);
            if (user == null) {
                throw new BusinessException(ErrorCode.ACCOUNT_NOT_BOUND);
            }
            if (user.getStatus() == null || user.getStatus() != 1) {
                throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
            }
        } else if (SendCodeRequest.SCENE_REGISTER.equals(request.scene())) {
            if (!loginProperties.isRegister()) {
                throw new BusinessException(ErrorCode.REGISTER_DISABLED);
            }
            if (byPhone ? findByPhone(target) != null : findByEmail(target) != null) {
                throw new BusinessException(byPhone ? ErrorCode.PHONE_EXISTS : ErrorCode.EMAIL_EXISTS);
            }
        }

        String code = authCodeService.issue(request.channel(), target, request.scene(), ip);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expireMinutes", loginProperties.getCodeExpireMinutes());
        body.put("resendAfterSeconds", loginProperties.getCodeIntervalSeconds());
        if (loginProperties.isExposeCodeInResponse()) {
            // 仅本地开发：便于自动化测试与联调，生产必须保持 false
            body.put("devCode", code);
        }
        return body;
    }

    /** 当前开放的登录方式（V1.2，供登录页渲染） */
    public LoginMethodsVO loginMethods() {
        List<String> channels = new ArrayList<>();
        if (loginProperties.isPhone()) {
            channels.add(SysAuthCode.CHANNEL_SMS);
        }
        if (loginProperties.isEmail()) {
            channels.add(SysAuthCode.CHANNEL_EMAIL);
        }
        return new LoginMethodsVO(
                loginProperties.isPassword(),
                loginProperties.isPhone(),
                loginProperties.isEmail(),
                loginProperties.wechatReady(),
                loginProperties.isRegister(),
                loginProperties.wechatReady() ? loginProperties.getWechat().getAppId() : null,
                loginProperties.getWechat().getScope(),
                channels);
    }

    /** 生成微信扫码地址（V1.2）。state 由后端下发并缓存，回调时校验 */
    public Map<String, String> wechatAuthUrl() {
        WechatAuthService.AuthUrl authUrl = wechatAuthService.buildAuthUrl();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("authUrl", authUrl.url());
        body.put("state", authUrl.state());
        return body;
    }

    /** 登录成功后的统一收尾：清失败计数、签发 Token、写日志 */
    private LoginResponse finishLogin(SysUser user, boolean rememberMe, String ip, String userAgent,
                                      HttpServletRequest httpRequest, String logMessage) {
        user.setFailCount(0);
        user.setLockUntil(null);
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        userContextService.invalidate(user.getId());
        LoginUser loginUser = userContextService.getLoginUser(user.getId(), null);
        long expireHours = configService.getInt("token_expire_hours", 8);
        if (rememberMe) {
            expireHours = REMEMBER_ME_HOURS;
        }
        JwtTokenService.IssuedToken issued = jwtTokenService.createToken(loginUser, expireHours);

        loginLog(httpRequest, user.getUsername(), user.getId(), 1, logMessage, ip, userAgent);
        operationLog(user.getUsername(), String.join(",", loginUser.roleNames()),
                "系统管理", "登录", null, ip, userAgent);

        return new LoginResponse(
                issued.token(),
                expireHours * 3600,
                loginUser.mustChangePwd(),
                toUserInfo(loginUser),
                permissionService.getMenuTreeByUserId(user.getId()));
    }

    private void ensureAccountEnabled(SysUser user, String logName, String ip, String userAgent,
                                      HttpServletRequest httpRequest) {
        if (user.getStatus() == null || user.getStatus() != 1) {
            loginLog(httpRequest, logName, user.getId(), 0, "账号已停用", ip, userAgent);
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        ensureNotLocked(user, logName, ip, userAgent, httpRequest);
    }

    private void ensureNotLocked(SysUser user, String logName, String ip, String userAgent,
                                 HttpServletRequest httpRequest) {
        if (user.getLockUntil() != null && user.getLockUntil().isAfter(LocalDateTime.now())) {
            long minutes = Duration.between(LocalDateTime.now(), user.getLockUntil()).toMinutes() + 1;
            loginLog(httpRequest, logName, user.getId(), 0, "账号锁定中", ip, userAgent);
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "账号已锁定，请约 " + minutes + " 分钟后重试");
        }
    }

    private void ensureCodeLoginEnabled(boolean byPhone) {
        if (byPhone ? !loginProperties.isPhone() : !loginProperties.isEmail()) {
            throw new BusinessException(ErrorCode.LOGIN_METHOD_DISABLED);
        }
    }

    private void ensureChannelEnabled(boolean byPhone) {
        ensureCodeLoginEnabled(byPhone);
    }

    private SysUser findByPhone(String phone) {
        return userMapper.selectOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhone, phone).last("LIMIT 1"));
    }

    private SysUser findByEmail(String email) {
        return userMapper.selectOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getEmail, email).last("LIMIT 1"));
    }

    /** 优先按 unionid 匹配（换绑后 openid 会变），回退按 openid 匹配 */
    private SysUser findByWechat(WechatAuthService.WechatUser wechatUser) {
        if (wechatUser.unionid() != null && !wechatUser.unionid().isBlank()) {
            SysUser byUnionid = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getWechatUnionid, wechatUser.unionid()).last("LIMIT 1"));
            if (byUnionid != null) {
                return byUnionid;
            }
        }
        return userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getWechatOpenid, wechatUser.openid()).last("LIMIT 1"));
    }

    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 登出：当前 Token 加入黑名单（IR-SW-03） */
    public void logout() {
        LoginUser user = SecurityUtils.getLoginUserOrNull();
        IssuedTokenHolder.CurrentToken holder = IssuedTokenHolder.current();
        if (holder != null) {
            SysTokenBlacklist blacklist = new SysTokenBlacklist();
            blacklist.setJti(holder.jti());
            blacklist.setExpireTime(holder.expireAt());
            blacklist.setReason(SysTokenBlacklist.REASON_LOGOUT);
            blacklistMapper.insert(blacklist);
            HttpServletRequest request = RequestUtils.currentRequest();
            if (user != null) {
                operationLog(user.username(), String.join(",", user.roleNames()),
                        "系统管理", "登出", null,
                        request != null ? RequestUtils.getClientIp(request) : null,
                        request != null ? request.getHeader("User-Agent") : null);
            }
        }
    }

    /** 当前用户信息（含菜单树，FR-SYS-05 前端动态渲染） */
    public MeResponse me() {
        LoginUser user = SecurityUtils.getLoginUser();
        List<MenuNode> menus = permissionService.getMenuTreeByUserId(user.userId());
        return new MeResponse(user.mustChangePwd(), toUserInfo(user), menus);
    }

    /** 修改自身密码（FR-SYS-03）：校验原密码；成功后所有端强制重新登录 */
    public void changePassword(String oldPassword, String newPassword) {
        LoginUser current = SecurityUtils.getLoginUser();
        SysUser user = userMapper.selectById(current.userId());
        if (user == null || !passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_ERROR);
        }
        String policyError = PasswordPolicy.validate(user.getUsername(), newPassword);
        if (policyError != null) {
            throw new BusinessException(ErrorCode.WEAK_PASSWORD, policyError);
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePwd(0);
        userMapper.updateById(user);

        // 上下文失效（密码指纹变化 → 其他端 Token 立即失效）+ 当前 Token 拉黑
        userContextService.invalidate(user.getId());
        IssuedTokenHolder.CurrentToken holder = IssuedTokenHolder.current();
        if (holder != null) {
            SysTokenBlacklist blacklist = new SysTokenBlacklist();
            blacklist.setJti(holder.jti());
            blacklist.setExpireTime(holder.expireAt());
            blacklist.setReason(SysTokenBlacklist.REASON_PWD_CHANGE);
            blacklistMapper.insert(blacklist);
        }
        HttpServletRequest request = RequestUtils.currentRequest();
        operationLog(user.getUsername(), String.join(",", current.roleNames()),
                "系统管理", "修改自身密码", null,
                request != null ? RequestUtils.getClientIp(request) : null,
                request != null ? request.getHeader("User-Agent") : null);
    }

    /** 密码错误处理：累加失败次数，达到阈值锁定（FR-SYS-01，参数可配） */
    private void handlePasswordFail(SysUser user, String username, String ip, String userAgent) {
        int failLimit = configService.getInt("login_fail_limit", 5);
        int lockMinutes = configService.getInt("lock_minutes", 15);
        int failCount = (user.getFailCount() == null ? 0 : user.getFailCount()) + 1;
        user.setFailCount(failCount);
        String message = "用户名或密码错误";
        if (failCount >= failLimit) {
            user.setLockUntil(LocalDateTime.now().plusMinutes(lockMinutes));
            user.setFailCount(0);
            message = "密码连续错误 " + failLimit + " 次，账号锁定 " + lockMinutes + " 分钟";
        }
        userMapper.updateById(user);
        loginLog(null, username, user.getId(), 0, message, ip, userAgent);
    }

    private void loginLog(HttpServletRequest request, String username, Long userId,
                           int status, String message, String ip, String userAgent) {
        LoginLog entry = new LoginLog();
        entry.setUsername(username);
        entry.setUserId(userId);
        entry.setIp(ip);
        entry.setBrowser(UserAgentUtils.parseBrowser(userAgent));
        entry.setOs(UserAgentUtils.parseOs(userAgent));
        entry.setStatus(status);
        entry.setMessage(message);
        entry.setLoginTime(LocalDateTime.now());
        loginLogService.recordAsync(entry);
    }

    private void operationLog(String username, String roleNames, String module,
                              String action, String target, String ip, String userAgent) {
        OperationLog entry = new OperationLog();
        entry.setUsername(username);
        entry.setRoleNames(roleNames);
        entry.setModule(module);
        entry.setAction(action);
        entry.setTarget(target);
        entry.setIp(ip);
        entry.setUserAgent(userAgent);
        entry.setResult(1);
        entry.setOperateTime(LocalDateTime.now());
        operationLogService.recordAsync(entry);
    }

    private UserInfo toUserInfo(LoginUser user) {
        return new UserInfo(user.userId(), user.username(), user.realName(),
                user.roleCodes(), user.roleNames(), user.permCodes());
    }
}
