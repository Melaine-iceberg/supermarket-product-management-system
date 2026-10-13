package com.smms.sys.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.sys.entity.SysConfig;
import com.smms.sys.mapper.SysConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 系统参数服务测试（FR-SYS-06 / GAP-P2-24）：
 * 生效方式与 instant_effect 标注一致——1 保存即清缓存（读取即新值）；
 * 0 不强制刷新缓存（既有值保持到缓存周期结束，无需重启系统）；值与只读校验。
 */
@ExtendWith(MockitoExtension.class)
class SysConfigServiceTest {

    @Mock
    private SysConfigMapper configMapper;

    private SysConfigService service;

    @BeforeEach
    void setUp() {
        service = new SysConfigService(configMapper);
    }

    private SysConfig config(String key, String value, String type, int instantEffect, int editable) {
        SysConfig config = new SysConfig();
        config.setId(1L);
        config.setConfigName(key);
        config.setConfigKey(key);
        config.setConfigValue(value);
        config.setValueType(type);
        config.setEditable(editable);
        config.setInstantEffect(instantEffect);
        return config;
    }

    @Test
    @DisplayName("instant_effect=1：保存后清缓存，读取立即使用新值")
    void instantEffectParamAppliesImmediately() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(
                config("export_max_rows", "1000", "NUMBER", 1, 1),
                config("export_max_rows", "2000", "NUMBER", 1, 1));

        assertThat(service.getStr("export_max_rows", "0")).isEqualTo("1000");
        service.update("export_max_rows", "2000", null, "admin");

        assertThat(service.getStr("export_max_rows", "0")).isEqualTo("2000");
    }

    @Test
    @DisplayName("instant_effect=0：不强制刷新缓存，缓存内保持旧值（最迟 60 秒自然生效，无需重启）")
    void nonInstantParamKeepsCachedValue() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(
                config("token_expire_hours", "8", "NUMBER", 0, 1),
                config("token_expire_hours", "12", "NUMBER", 0, 1));

        assertThat(service.getInt("token_expire_hours", 8)).isEqualTo(8);
        service.update("token_expire_hours", "12", null, "admin");

        // 缓存未被主动清除：本次读取仍是旧值，不是"重启后生效"的误导标注
        assertThat(service.getInt("token_expire_hours", 8)).isEqualTo(8);
    }

    @Test
    @DisplayName("只读参数拒绝修改")
    void readOnlyParamRejected() {
        when(configMapper.selectOne(any(Wrapper.class)))
                .thenReturn(config("store_name", "好又多超市", "STRING", 1, 0));

        assertThatThrownBy(() -> service.update("store_name", "新名称", null, "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只读");
    }

    @Test
    @DisplayName("值类型校验：BOOLEAN 非法值拒绝，不落库")
    void invalidValueTypeRejected() {
        when(configMapper.selectOne(any(Wrapper.class)))
                .thenReturn(config("allow_negative_stock", "false", "BOOLEAN", 1, 1));

        assertThatThrownBy(() -> service.update("allow_negative_stock", "maybe", null, "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("参数不存在返回 404 业务码")
    void missingParamRejected() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.update("not_exists", "1", null, "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }
}
