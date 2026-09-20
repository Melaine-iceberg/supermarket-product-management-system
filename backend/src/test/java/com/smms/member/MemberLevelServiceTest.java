package com.smms.member;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.exception.BusinessException;
import com.smms.member.dto.LevelSaveRequest;
import com.smms.member.entity.MemberLevel;
import com.smms.member.mapper.MemberLevelMapper;
import com.smms.member.service.MemberLevelService;
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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会员等级规则单元测试（FR-MEM-05）：
 * 新增/编辑（名称唯一、门槛/折扣率/倍率校验）、停用/启用、列表排序。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberLevelServiceTest {

    @Mock private MemberLevelMapper memberLevelMapper;

    private MemberLevelService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MemberLevel.class);
        service = new MemberLevelService(memberLevelMapper);
        when(memberLevelMapper.selectCount(any())).thenReturn(0L);
        when(memberLevelMapper.selectById(1L)).thenReturn(level(1L, "普通会员"));
    }

    private MemberLevel level(Long id, String name) {
        MemberLevel l = new MemberLevel();
        l.setId(id);
        l.setName(name);
        return l;
    }

    private LevelSaveRequest req(String name, String threshold, String discount, String pointRate, int sortNo) {
        LevelSaveRequest r = new LevelSaveRequest();
        r.setName(name);
        r.setThreshold(new BigDecimal(threshold));
        r.setDiscountRate(new BigDecimal(discount));
        r.setPointRate(new BigDecimal(pointRate));
        r.setSortNo(sortNo);
        r.setStatus(1);
        return r;
    }

    @Test
    @DisplayName("新增等级：字段落库（FR-MEM-05）")
    void create_success() {
        service.create(req("铂金会员", "20000", "0.90", "1.5", 4), "王店长");

        ArgumentCaptor<MemberLevel> captor = ArgumentCaptor.forClass(MemberLevel.class);
        verify(memberLevelMapper).insert(captor.capture());
        MemberLevel saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("铂金会员");
        assertThat(saved.getThreshold()).isEqualByComparingTo("20000");
        assertThat(saved.getDiscountRate()).isEqualByComparingTo("0.90");
        assertThat(saved.getPointRate()).isEqualByComparingTo("1.5");
        assertThat(saved.getSortNo()).isEqualTo(4);
        assertThat(saved.getUpdateBy()).isEqualTo("王店长");
    }

    @Test
    @DisplayName("新增等级：名称重复拒绝（uk_level_name）")
    void create_nameExists() {
        when(memberLevelMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.create(req("银卡会员", "1000", "0.98", "1.0", 2), "王店长"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(memberLevelMapper, never()).insert(any(MemberLevel.class));
    }

    @Test
    @DisplayName("编辑等级：改名保持唯一（排除自身）")
    void update_success() {
        service.update(1L, req("普通会员", "0", "1.0000", "1.0", 1), "王店长");

        ArgumentCaptor<MemberLevel> captor = ArgumentCaptor.forClass(MemberLevel.class);
        verify(memberLevelMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1L);
        assertThat(captor.getValue().getDiscountRate()).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("编辑等级：等级不存在拒绝")
    void update_notFound() {
        when(memberLevelMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.update(99L, req("钻石", "0", "0.90", "1", 9), "王店长"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("等级不存在");
    }

    @Test
    @DisplayName("停用/启用等级")
    void changeStatus() {
        when(memberLevelMapper.selectById(2L)).thenReturn(level(2L, "银卡会员"));
        service.changeStatus(2L, 0);

        ArgumentCaptor<MemberLevel> captor = ArgumentCaptor.forClass(MemberLevel.class);
        verify(memberLevelMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(2L);
        assertThat(captor.getValue().getStatus()).isZero();
    }

    @Test
    @DisplayName("列表：按排序升序返回全部（含停用）")
    void list_ordered() {
        MemberLevel gold = level(3L, "金卡会员");
        gold.setSortNo(3);
        MemberLevel silver = level(2L, "银卡会员");
        silver.setSortNo(2);
        when(memberLevelMapper.selectList(any())).thenReturn(List.of(silver, gold));

        List<MemberLevel> list = service.list();

        assertThat(list).extracting(MemberLevel::getName).containsExactly("银卡会员", "金卡会员");
    }
}
