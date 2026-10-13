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
import com.smms.sys.dto.LoginRequest;
import com.smms.sys.dto.LoginResponse;
import com.smms.sys.dto.MeResponse;
import com.smms.sys.dto.MenuNode;
import com.smms.sys.dto.UserInfo;
import com.smms.sys.entity.LoginLog;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.entity.SysTokenBlacklist;
import com.smms.sys.entity.SysUser;
import com.smms.sys.mapper.SysTokenBlacklistMapper;
import com.smms.sys.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

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
    private final SysTokenBlacklistMapper blacklistMapper;
    private final JwtTokenService jwtTokenService;
    private final UserContextService userContextService;
    private final SysConfigService configService;
    private final SysPermissionService permissionService;
    private final LoginLogService loginLogService;
    private final OperationLogService operationLogService;
    private final LoginRateLimiter loginRateLimiter;
    private final PasswordEncoder passwordEncoder;

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
        if (user.getLockUntil() != null && user.getLockUntil().isAfter(LocalDateTime.now())) {
            long minutes = Duration.between(LocalDateTime.now(), user.getLockUntil()).toMinutes() + 1;
            loginLog(httpRequest, request.username(), user.getId(), 0, "账号锁定中", ip, userAgent);
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
                    "账号已锁定，请约 " + minutes + " 分钟后重试");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            handlePasswordFail(user, request.username(), ip, userAgent);
            throw new BusinessException(ErrorCode.USERNAME_PASSWORD_ERROR);
        }

        // 登录成功：清零失败计数、记录最后登录时间（FR-SYS-01）
        user.setFailCount(0);
        user.setLockUntil(null);
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        userContextService.invalidate(user.getId());
        LoginUser loginUser = userContextService.getLoginUser(user.getId(), null);
        long expireHours = configService.getInt("token_expire_hours", 8);
        if (Boolean.TRUE.equals(request.rememberMe())) {
            expireHours = REMEMBER_ME_HOURS;
        }
        JwtTokenService.IssuedToken issued = jwtTokenService.createToken(loginUser, expireHours);

        loginLog(httpRequest, user.getUsername(), user.getId(), 1, "登录成功", ip, userAgent);
        operationLog(user.getUsername(), loginUser.realName(), String.join(",", loginUser.roleNames()),
                "系统管理", "登录", null, ip, userAgent);

        return new LoginResponse(
                issued.token(),
                expireHours * 3600,
                loginUser.mustChangePwd(),
                toUserInfo(loginUser),
                permissionService.getMenuTreeByUserId(user.getId()));
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
                operationLog(user.username(), user.realName(), String.join(",", user.roleNames()),
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

    /**
     * 黑名单清理（IR-SW-06）：删除已过期的黑名单记录（Token 已自然过期，无需再比对，GAP-P1-11），返回删除条数。
     * 幂等：按过期时间条件删除；调度由 DynamicTaskScheduler 统一注册（GAP-P1-03）。
     */
    public int cleanExpiredBlacklist() {
        LocalDateTime now = LocalDateTime.now();
        int deleted = blacklistMapper.delete(Wrappers.<SysTokenBlacklist>lambdaQuery()
                .lt(SysTokenBlacklist::getExpireTime, now));
        if (deleted > 0) {
            log.info("Token 黑名单清理：删除已过期记录 {} 条", deleted);
        }
        return deleted;
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
        operationLog(user.getUsername(), user.getRealName(), String.join(",", current.roleNames()),
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

    private void operationLog(String username, String realName, String roleNames, String module,
                              String action, String target, String ip, String userAgent) {
        OperationLog entry = new OperationLog();
        entry.setUsername(username);
        entry.setRealName(realName);
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
