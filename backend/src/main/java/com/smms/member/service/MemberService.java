package com.smms.member.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.common.util.MaskUtils;
import com.smms.member.dto.MemberCreateRequest;
import com.smms.member.dto.MemberListVO;
import com.smms.member.dto.MemberQuery;
import com.smms.member.dto.MemberUpdateRequest;
import com.smms.member.entity.Member;
import com.smms.member.entity.MemberLevel;
import com.smms.member.mapper.MemberLevelMapper;
import com.smms.member.mapper.MemberMapper;
import com.smms.security.SecurityUtils;
import com.smms.common.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 会员档案服务（FR-MEM-01~03）：
 * 注册（自动编号 M+8位流水、初始普通会员）、信息维护（手机号改须唯一校验、
 * 积分/储值/累计消费不可手工改）、停用/启用、多维分页查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    /** 手机号规则：大陆 11 位（BR-MEM-01） */
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    /** 会员编号日流水上限：M+8 位（BR-MEM-02） */
    private static final int MAX_SEQ = 99_999_999;

    private static final int RETRY = 3;

    private final MemberMapper memberMapper;
    private final MemberLevelMapper memberLevelMapper;

    // ==================== 注册（FR-MEM-01） ====================

    /**
     * 注册会员：姓名与手机号必填；自动生成会员编号（M+8位流水）；
     * 初始等级取启用中门槛最低的等级（普通会员）；积分/储值/累计消费均为 0。
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberListVO create(MemberCreateRequest req, String operatorRealName) {
        String phone = req.getPhone().trim();
        checkPhoneUnique(phone, null);

        MemberLevel level = defaultLevel();
        Member member = new Member();
        member.setMemberNo(nextMemberNo());
        member.setName(req.getName().trim());
        member.setPhone(phone);
        member.setGender(req.getGender());
        member.setBirthday(req.getBirthday());
        member.setAddress(req.getAddress());
        member.setLevelId(level.getId());
        member.setPoints(0);
        member.setBalance(BigDecimal.ZERO);
        member.setTotalConsume(BigDecimal.ZERO);
        member.setConsumeTimes(0);
        member.setRegisterName(operatorRealName);
        member.setRegisterTime(LocalDateTime.now());
        member.setStatus(1);
        member.setRemark(TextSanitizer.sanitize(req.getRemark()));
        insertWithRetry(member);
        return toVO(member, level);
    }

    // ==================== 信息维护（FR-MEM-02） ====================

    /** 编辑档案：姓名/手机号/性别/生日/地址/备注；手机号修改须保持唯一 */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, MemberUpdateRequest req) {
        Member member = requireMember(id);
        String phone = req.getPhone().trim();
        checkPhoneUnique(phone, id);
        member.setName(req.getName().trim());
        member.setPhone(phone);
        member.setGender(req.getGender());
        member.setBirthday(req.getBirthday());
        member.setAddress(req.getAddress());
        member.setRemark(TextSanitizer.sanitize(req.getRemark()));
        memberMapper.updateById(member);
    }

    /** 停用/启用（BR-MEM-03）：停用后不享受会员价与折扣，档案永久保留 */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        Member member = requireMember(id);
        member.setStatus(status);
        memberMapper.updateById(member);
    }

    // ==================== 查询（FR-MEM-03） ====================

    /** 分页多维检索：编号/姓名/手机号模糊，等级/状态精确，注册日期区间 */
    public PageVO<MemberListVO> page(MemberQuery q) {
        int page = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        String memberNo = trimToNull(q.memberNo());
        String name = trimToNull(q.name());
        String phone = trimToNull(q.phone());
        LocalDateTime begin = q.beginDate() == null || q.beginDate().isBlank()
                ? null : LocalDate.parse(q.beginDate()).atStartOfDay();
        LocalDateTime end = q.endDate() == null || q.endDate().isBlank()
                ? null : LocalDate.parse(q.endDate()).atTime(23, 59, 59);
        LambdaQueryWrapper<Member> wrapper = Wrappers.<Member>lambdaQuery()
                .like(memberNo != null, Member::getMemberNo, memberNo)
                .like(name != null, Member::getName, name)
                .like(phone != null, Member::getPhone, phone)
                .eq(q.levelId() != null, Member::getLevelId, q.levelId())
                .eq(q.status() != null, Member::getStatus, q.status())
                .ge(begin != null, Member::getRegisterTime, begin)
                .le(end != null, Member::getRegisterTime, end)
                .orderByDesc(Member::getRegisterTime)
                .orderByDesc(Member::getId);
        Page<Member> result = memberMapper.selectPage(new Page<>(page, size), wrapper);
        return new PageVO<>(mapWithLevel(result.getRecords()), result.getTotal());
    }

    /** 会员详情（含等级名称与业务统计） */
    public MemberListVO detail(Long id) {
        Member member = requireMember(id);
        MemberLevel level = memberLevelMapper.selectById(member.getLevelId());
        return toVO(member, level);
    }

    // ==================== 内部方法 ====================

    private Member requireMember(Long id) {
        Member member = memberMapper.selectById(id);
        if (member == null) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
        return member;
    }

    /** 手机号全局唯一校验（含停用会员；BR-MEM-01） */
    private void checkPhoneUnique(String phone, Long excludeId) {
        if (!PHONE.matcher(phone).matches()) {
            throw new BusinessException(ErrorCode.MEMBER_PHONE_INVALID);
        }
        Long count = memberMapper.selectCount(Wrappers.<Member>lambdaQuery()
                .eq(Member::getPhone, phone)
                .ne(excludeId != null, Member::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.MEMBER_PHONE_EXISTS);
        }
    }

    /** 初始等级：启用中门槛最低的等级（通常为"普通会员"） */
    private MemberLevel defaultLevel() {
        return memberLevelMapper.selectList(Wrappers.<MemberLevel>lambdaQuery()
                        .eq(MemberLevel::getStatus, 1)
                        .orderByAsc(MemberLevel::getThreshold)
                        .orderByAsc(MemberLevel::getSortNo)
                        .last("LIMIT 1"))
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_LEVEL_NO_ENABLED));
    }

    /** 生成下一会员编号（BR-MEM-02）：行锁取当日最大号，uk 兜底防重 */
    private String nextMemberNo() {
        String last = memberMapper.selectLastMemberNoForUpdate();
        int seq = last == null ? 1 : Integer.parseInt(last.substring(1)) + 1;
        if (seq > MAX_SEQ) {
            throw new BusinessException(ErrorCode.MEMBER_NO_EXCEED);
        }
        return "M%08d".formatted(seq);
    }

    /** 插入并对编号唯一键冲突重试（并发取号兜底） */
    private void insertWithRetry(Member member) {
        for (int i = 0; i < RETRY; i++) {
            try {
                memberMapper.insert(member);
                return;
            } catch (DuplicateKeyException e) {
                if (i == RETRY - 1) {
                    throw e;
                }
                log.warn("会员编号 {} 冲突，自动重试取号", member.getMemberNo());
                member.setMemberNo(nextMemberNo());
            }
        }
    }

    private List<MemberListVO> mapWithLevel(List<Member> members) {
        if (members.isEmpty()) {
            return List.of();
        }
        List<Long> levelIds = members.stream().map(Member::getLevelId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, MemberLevel> levelMap = levelIds.isEmpty() ? Map.of()
                : memberLevelMapper.selectBatchIds(levelIds).stream()
                .collect(Collectors.toMap(MemberLevel::getId, Function.identity()));
        return members.stream()
                .map(m -> toVO(m, levelMap.get(m.getLevelId())))
                .toList();
    }

    private MemberListVO toVO(Member member, MemberLevel level) {
        MemberListVO vo = new MemberListVO();
        vo.setId(member.getId());
        vo.setMemberNo(member.getMemberNo());
        vo.setName(member.getName());
        // 手机号按角色脱敏（BR-MEM-08：完整手机号仅店长及以上可见）
        vo.setPhone(SecurityUtils.canViewMemberPhone() ? member.getPhone() : MaskUtils.maskPhone(member.getPhone()));
        vo.setGender(member.getGender());
        vo.setBirthday(member.getBirthday());
        vo.setAddress(member.getAddress());
        vo.setLevelId(member.getLevelId());
        vo.setLevelName(level == null ? null : level.getName());
        vo.setPoints(member.getPoints());
        vo.setBalance(member.getBalance());
        vo.setTotalConsume(member.getTotalConsume());
        vo.setConsumeTimes(member.getConsumeTimes());
        vo.setLastConsumeTime(member.getLastConsumeTime());
        vo.setRegisterName(member.getRegisterName());
        vo.setRegisterTime(member.getRegisterTime());
        vo.setStatus(member.getStatus());
        vo.setRemark(member.getRemark());
        return vo;
    }

    private String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
