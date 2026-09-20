package com.smms.member;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.exception.BusinessException;
import com.smms.member.dto.MemberCreateRequest;
import com.smms.member.dto.MemberListVO;
import com.smms.member.dto.MemberQuery;
import com.smms.member.dto.MemberUpdateRequest;
import com.smms.member.entity.Member;
import com.smms.member.entity.MemberLevel;
import com.smms.member.mapper.MemberLevelMapper;
import com.smms.member.mapper.MemberMapper;
import com.smms.member.service.MemberService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会员档案单元测试（FR-MEM-01~03）：
 * 注册（编号生成、初始等级与统计清零）、手机号唯一、编辑、停用/启用、分页筛选。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberServiceTest {

    @Mock private MemberMapper memberMapper;
    @Mock private MemberLevelMapper memberLevelMapper;

    private MemberService service;

    private final MemberLevel normal = level(1L, "普通会员", "0", "1.0000", "1.0", 1);
    private final MemberLevel silver = level(2L, "银卡会员", "1000", "0.9800", "1.0", 2);

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Member.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MemberLevel.class);
        service = new MemberService(memberMapper, memberLevelMapper);

        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(memberLevelMapper.selectList(any())).thenReturn(List.of(normal, silver));
        when(memberMapper.selectLastMemberNoForUpdate()).thenReturn(null);
        doAnswer(inv -> {
            Member m = inv.getArgument(0);
            m.setId(100L);
            return 1;
        }).when(memberMapper).insert(any(Member.class));
    }

    // ---------- 造数 ----------

    private MemberLevel level(Long id, String name, String threshold, String discount, String pointRate, int sortNo) {
        MemberLevel l = new MemberLevel();
        l.setId(id);
        l.setName(name);
        l.setThreshold(new BigDecimal(threshold));
        l.setDiscountRate(new BigDecimal(discount));
        l.setPointRate(new BigDecimal(pointRate));
        l.setSortNo(sortNo);
        l.setStatus(1);
        return l;
    }

    private MemberCreateRequest createReq(String name, String phone) {
        MemberCreateRequest req = new MemberCreateRequest();
        req.setName(name);
        req.setPhone(phone);
        req.setGender(2);
        req.setBirthday(LocalDate.of(1990, 1, 1));
        req.setAddress("南京市玄武区");
        req.setRemark("VIP 介绍");
        return req;
    }

    // ---------- 注册（FR-MEM-01） ----------

    @Test
    @DisplayName("注册：编号 M+8 位流水，初始门槛最低等级，统计清零（BR-MEM-02）")
    void create_success() {
        when(memberMapper.selectLastMemberNoForUpdate()).thenReturn("M00000009");

        MemberListVO vo = service.create(createReq("张三", "13800001111"), "陈收银");

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).insert(captor.capture());
        Member saved = captor.getValue();
        assertThat(saved.getMemberNo()).isEqualTo("M00000010");
        assertThat(saved.getName()).isEqualTo("张三");
        assertThat(saved.getPhone()).isEqualTo("13800001111");
        assertThat(saved.getLevelId()).isEqualTo(1L); // 门槛最低的启用等级
        assertThat(saved.getPoints()).isZero();
        assertThat(saved.getBalance()).isEqualByComparingTo("0");
        assertThat(saved.getTotalConsume()).isEqualByComparingTo("0");
        assertThat(saved.getConsumeTimes()).isZero();
        assertThat(saved.getRegisterName()).isEqualTo("陈收银");
        assertThat(saved.getStatus()).isEqualTo(1);

        assertThat(vo.getLevelName()).isEqualTo("普通会员");
        assertThat(vo.getMemberNo()).isEqualTo("M00000010");
    }

    @Test
    @DisplayName("注册：库内无会员时从 M00000001 起")
    void create_firstMember() {
        when(memberMapper.selectLastMemberNoForUpdate()).thenReturn(null);

        MemberListVO vo = service.create(createReq("李四", "13800002222"), "陈收银");

        assertThat(vo.getMemberNo()).isEqualTo("M00000001");
    }

    @Test
    @DisplayName("注册：手机号非法/已注册拒绝（BR-MEM-01）")
    void create_phoneInvalid() {
        assertThatThrownBy(() -> service.create(createReq("张三", "12800001111"), "陈收银"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("11 位手机号");

        when(memberMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.create(createReq("张三", "13800001111"), "陈收银"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已注册");

        verify(memberMapper, never()).insert(any(Member.class));
    }

    @Test
    @DisplayName("注册：编号唯一键冲突自动重试（并发兜底）")
    void create_duplicateRetry() {
        when(memberMapper.selectLastMemberNoForUpdate())
                .thenReturn("M00000001")   // 第一次取号
                .thenReturn("M00000002");  // 冲突后重取（他人已插入 2 号）
        when(memberMapper.insert(any(Member.class)))
                .thenThrow(new DuplicateKeyException("uk_member_no"))
                .thenReturn(1);

        MemberListVO vo = service.create(createReq("王五", "13800003333"), "陈收银");

        assertThat(vo.getMemberNo()).isEqualTo("M00000003");
        verify(memberMapper, times(2)).insert(any(Member.class));
    }

    // ---------- 信息维护（FR-MEM-02） ----------

    @Test
    @DisplayName("编辑：姓名/性别/生日/地址/备注可改，手机号改须唯一（排除自身）")
    void update_success() {
        Member existing = member(1L, "M00000001", "张三", "13800001111");
        when(memberMapper.selectById(1L)).thenReturn(existing);

        MemberUpdateRequest req = new MemberUpdateRequest();
        req.setName("张三丰");
        req.setPhone("13900001111");
        req.setGender(1);
        req.setAddress("新地址");
        service.update(1L, req);

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).updateById(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("张三丰");
        assertThat(captor.getValue().getPhone()).isEqualTo("13900001111");
        // 积分/储值/累计消费不在编辑面（不可手工直接修改）
        assertThat(captor.getValue().getPoints()).isEqualTo(5);
        assertThat(captor.getValue().getBalance()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("编辑：手机号与他人重复拒绝（BR-MEM-01）")
    void update_phoneDuplicated() {
        when(memberMapper.selectById(1L)).thenReturn(member(1L, "M00000001", "张三", "13800001111"));
        when(memberMapper.selectCount(any())).thenReturn(1L);

        MemberUpdateRequest req = new MemberUpdateRequest();
        req.setName("张三");
        req.setPhone("13899998888");
        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已注册");
        verify(memberMapper, never()).updateById(any(Member.class));
    }

    @Test
    @DisplayName("停用/启用会员（BR-MEM-03）")
    void changeStatus() {
        when(memberMapper.selectById(1L)).thenReturn(member(1L, "M00000001", "张三", "13800001111"));

        service.changeStatus(1L, 0);

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isZero();
    }

    // ---------- 查询（FR-MEM-03） ----------

    @Test
    @DisplayName("分页：注册时间倒序 + 等级名称映射")
    void page_withLevelNames() {
        Member m1 = member(1L, "M00000001", "张三", "13800001111");
        m1.setLevelId(2L);
        Member m2 = member(2L, "M00000002", "李四", "13800002222");
        Page<Member> result = new Page<>(1, 20);
        result.setRecords(List.of(m1, m2));
        result.setTotal(2);
        when(memberMapper.selectPage(any(), any())).thenReturn(result);
        when(memberLevelMapper.selectBatchIds(any())).thenReturn(List.of(normal, silver));

        var page = service.page(new MemberQuery(null, null, null, null, null, null, null, 1, 20));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.list()).hasSize(2);
        assertThat(page.list().get(0).getLevelName()).isEqualTo("银卡会员");
        assertThat(page.list().get(1).getLevelName()).isEqualTo("普通会员");
    }

    @Test
    @DisplayName("分页：无数据返回空列表不查等级")
    void page_empty() {
        Page<Member> result = new Page<>(1, 20);
        result.setRecords(List.of());
        result.setTotal(0);
        when(memberMapper.selectPage(any(), any())).thenReturn(result);

        var page = service.page(new MemberQuery(null, null, null, null, null, null, null, 1, 20));

        assertThat(page.list()).isEmpty();
        verify(memberLevelMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("详情：会员不存在报错（FR-MEM-03）")
    void detail_notFound() {
        when(memberMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.detail(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会员不存在");
    }

    private Member member(Long id, String memberNo, String name, String phone) {
        Member m = new Member();
        m.setId(id);
        m.setMemberNo(memberNo);
        m.setName(name);
        m.setPhone(phone);
        m.setLevelId(1L);
        m.setPoints(5);
        m.setBalance(new BigDecimal("100"));
        m.setTotalConsume(BigDecimal.ZERO);
        m.setConsumeTimes(0);
        m.setStatus(1);
        return m;
    }
}
