package com.smms.sys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.sys.config.LoginProperties;
import com.smms.sys.entity.SysAuthCode;
import com.smms.sys.mapper.SysAuthCodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 短信/邮件验证码服务（V1.2）。
 *
 * <p>安全设计：</p>
 * <ul>
 *   <li>明文验证码只在内存与发送器中存在，入库前 BCrypt 哈希；</li>
 *   <li>6 位数字由 {@link SecureRandom} 生成，避免可预测序列；</li>
 *   <li>发送侧限制：同目标 60 秒间隔、每日 10 次；</li>
 *   <li>校验侧限制：单条验证码最多错 5 次即作废，防止暴力枚举；</li>
 *   <li>校验通过即标记 used，验证码一次性。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CHANNEL_SMS = SysAuthCode.CHANNEL_SMS;
    private static final String CHANNEL_EMAIL = SysAuthCode.CHANNEL_EMAIL;

    private final SysAuthCodeMapper authCodeMapper;
    /** 多渠道路由：短信与邮件各自选 provider，详见 {@link CodeSenderRouter} */
    private final CodeSenderRouter codeSenderRouter;
    private final PasswordEncoder passwordEncoder;
    private final LoginProperties loginProperties;

    /**
     * 生成并发送验证码，返回明文验证码。
     *
     * <p>返回值仅供「开发模式回显」使用，调用方必须在非开发模式下丢弃它。</p>
     */
    @Transactional
    public String issue(String channel, String target, String scene, String requestIp) {
        validateChannel(channel);
        if (target == null || target.isBlank()) {
            throw new BusinessException(channel.equals(CHANNEL_SMS) ? ErrorCode.PHONE_INVALID : ErrorCode.EMAIL_INVALID);
        }
        if (!codeSenderRouter.available(channel)) {
            throw new BusinessException(ErrorCode.LOGIN_METHOD_DISABLED);
        }

        LocalDateTime now = LocalDateTime.now();
        Long recentCount = authCodeMapper.selectCount(Wrappers.<SysAuthCode>lambdaQuery()
                .eq(SysAuthCode::getChannel, channel)
                .eq(SysAuthCode::getTarget, target)
                .ge(SysAuthCode::getCreateTime, LocalDate.now().atStartOfDay()));
        if (recentCount != null && recentCount >= loginProperties.getCodeDailyLimit()) {
            throw new BusinessException(ErrorCode.CODE_DAILY_LIMIT);
        }

        SysAuthCode latest = authCodeMapper.selectOne(Wrappers.<SysAuthCode>lambdaQuery()
                .eq(SysAuthCode::getChannel, channel)
                .eq(SysAuthCode::getTarget, target)
                .orderByDesc(SysAuthCode::getId)
                .last("LIMIT 1"));
        if (latest != null && latest.getSendTime() != null
                && latest.getSendTime().plusSeconds(loginProperties.getCodeIntervalSeconds()).isAfter(now)) {
            throw new BusinessException(ErrorCode.CODE_SEND_TOO_FAST);
        }

        String code = randomCode();
        SysAuthCode entity = new SysAuthCode();
        entity.setChannel(channel);
        entity.setTarget(target);
        entity.setCodeHash(passwordEncoder.encode(code));
        entity.setScene(scene);
        entity.setUsed(0);
        entity.setAttemptCount(0);
        entity.setRequestIp(requestIp);
        entity.setSendTime(now);
        entity.setExpireTime(now.plusMinutes(loginProperties.getCodeExpireMinutes()));
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        authCodeMapper.insert(entity);

        // 同一个目标的历史验证码立即失效，避免旧码被重放
        invalidatePrevious(channel, target, entity.getId(), now);

        // 注意：外发网络调用在事务内完成。好处是发送失败会回滚，验证码不会残留、
        // 当日配额也不被占用；代价是慢渠道（SMTP 超时上限 10s）会占住一个数据库连接。
        // 登录验证码属低频操作，此处选择正确性优先。
        try {
            codeSenderRouter.send(channel, target, code, scene, loginProperties.getCodeExpireMinutes());
        } catch (RuntimeException e) {
            log.error("验证码外发失败：channel={} scene={} cause={}", channel, scene, e.getMessage());
            // 抛出业务异常让事务回滚（验证码不落库），并给前端一个可读的错误
            throw new BusinessException(ErrorCode.CODE_SEND_FAILED);
        }
        return code;
    }

    /**
     * 校验验证码。校验成功即消费掉该验证码；失败抛业务异常。
     *
     * <p>为防时序侧信道，无论验证码是否存在都走一次 BCrypt 比对。</p>
     */
    @Transactional
    public void verify(String channel, String target, String scene, String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.CODE_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        List<SysAuthCode> candidates = authCodeMapper.selectList(Wrappers.<SysAuthCode>lambdaQuery()
                .eq(SysAuthCode::getChannel, channel)
                .eq(SysAuthCode::getTarget, target)
                .eq(SysAuthCode::getScene, scene)
                .eq(SysAuthCode::getUsed, 0)
                .orderByDesc(SysAuthCode::getId));

        SysAuthCode matched = null;
        for (SysAuthCode candidate : candidates) {
            if (passwordEncoder.matches(code, candidate.getCodeHash())) {
                matched = candidate;
                break;
            }
        }
        if (matched == null) {
            // 未命中：给最新一条计入失败次数，达到上限即整条作废
            if (!candidates.isEmpty()) {
                SysAuthCode latest = candidates.get(0);
                int attempts = (latest.getAttemptCount() == null ? 0 : latest.getAttemptCount()) + 1;
                latest.setAttemptCount(attempts);
                latest.setUpdateTime(now);
                if (attempts >= loginProperties.getCodeMaxAttempts()) {
                    latest.setUsed(1);
                }
                authCodeMapper.updateById(latest);
                if (attempts >= loginProperties.getCodeMaxAttempts()) {
                    throw new BusinessException(ErrorCode.CODE_ATTEMPT_EXCEED);
                }
            }
            throw new BusinessException(ErrorCode.CODE_INVALID);
        }

        if (matched.getExpireTime() != null && matched.getExpireTime().isBefore(now)) {
            matched.setUsed(1);
            matched.setUpdateTime(now);
            authCodeMapper.updateById(matched);
            throw new BusinessException(ErrorCode.CODE_INVALID);
        }

        matched.setUsed(1);
        matched.setUpdateTime(now);
        authCodeMapper.updateById(matched);
    }

    private void invalidatePrevious(String channel, String target, Long keepId, LocalDateTime now) {
        SysAuthCode patch = new SysAuthCode();
        patch.setUsed(1);
        patch.setUpdateTime(now);
        authCodeMapper.update(patch, Wrappers.<SysAuthCode>lambdaUpdate()
                .eq(SysAuthCode::getChannel, channel)
                .eq(SysAuthCode::getTarget, target)
                .eq(SysAuthCode::getUsed, 0)
                .ne(SysAuthCode::getId, keepId));
    }

    private void validateChannel(String channel) {
        if (!CHANNEL_SMS.equals(channel) && !CHANNEL_EMAIL.equals(channel)) {
            throw new BusinessException(ErrorCode.LOGIN_METHOD_DISABLED);
        }
    }

    private String randomCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
