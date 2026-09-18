package com.smms.sys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.sys.entity.SysConfig;
import com.smms.sys.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统参数服务（FR-SYS-06）：键值对业务参数，60 秒缓存；
 * instant_effect=1 的参数修改后立即对读取方生效。
 */
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private static final long TTL_MILLIS = 60_000L;

    private record CacheEntry(String value, long cachedAt) {
    }

    private final SysConfigMapper configMapper;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public String getStr(String key, String defaultValue) {
        CacheEntry entry = cache.get(key);
        if (entry != null && System.currentTimeMillis() - entry.cachedAt() < TTL_MILLIS) {
            return entry.value() != null ? entry.value() : defaultValue;
        }
        SysConfig config = configMapper.selectOne(
                Wrappers.<SysConfig>lambdaQuery().eq(SysConfig::getConfigKey, key));
        String value = config != null ? config.getConfigValue() : null;
        cache.put(key, new CacheEntry(value, System.currentTimeMillis()));
        return value != null ? value : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        try {
            return (int) Math.round(Double.parseDouble(getStr(key, String.valueOf(defaultValue))));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBool(String key, boolean defaultValue) {
        String value = getStr(key, String.valueOf(defaultValue));
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    /** 全部参数（分组展示用） */
    public List<SysConfig> listAll() {
        return configMapper.selectList(Wrappers.<SysConfig>lambdaQuery()
                .orderByAsc(SysConfig::getConfigGroup)
                .orderByAsc(SysConfig::getId));
    }

    /** 修改参数（FR-SYS-06：修改需权限并留痕，校验值类型） */
    public void update(String key, String value, String remark, String updateBy) {
        SysConfig config = configMapper.selectOne(
                Wrappers.<SysConfig>lambdaQuery().eq(SysConfig::getConfigKey, key));
        if (config == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "参数不存在：" + key);
        }
        if (config.getEditable() == null || config.getEditable() != 1) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该参数为只读参数，不允许修改");
        }
        validateValue(config, value);
        config.setConfigValue(value);
        if (remark != null) {
            config.setRemark(remark);
        }
        config.setUpdateBy(updateBy);
        configMapper.updateById(config);
        cache.remove(key);
    }

    private void validateValue(SysConfig config, String value) {
        String type = config.getValueType() == null ? "STRING" : config.getValueType();
        switch (type) {
            case "NUMBER" -> {
                try {
                    Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "参数值必须为数字");
                }
            }
            case "BOOLEAN" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "参数值必须为 true 或 false");
                }
            }
            case "TIME" -> {
                try {
                    LocalTime.parse(value);
                } catch (Exception e) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "参数值必须为 HH:mm 格式");
                }
            }
            default -> { /* STRING 不校验 */ }
        }
    }

    public String getStoreName() {
        return getStr("store_name", "超市");
    }
}
