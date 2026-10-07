package com.smms.common.task;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务参数快照工具：以「键, 值, 键, 值...」的成对形式构造参数表，
 * 值为 null 时跳过（{@code Map.of} 不接受 null，故不适用）。
 */
public final class TaskParams {

    private TaskParams() {
    }

    public static Map<String, Object> of(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("参数必须成对出现");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                map.put(String.valueOf(keyValues[i]), value);
            }
        }
        return map;
    }
}
