package com.smms.sys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.sys.dto.DictItemSaveRequest;
import com.smms.sys.dto.DictVO;
import com.smms.sys.entity.SysDict;
import com.smms.sys.entity.SysDictItem;
import com.smms.sys.mapper.SysDictItemMapper;
import com.smms.sys.mapper.SysDictMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据字典服务（FR-SYS-07）：维护计量单位、收款方式、退货原因等枚举；
 * 停用项不可被新单据选择，历史数据仍能正确显示名称。
 */
@Service
@RequiredArgsConstructor
public class SysDictService {

    private final SysDictMapper dictMapper;
    private final SysDictItemMapper itemMapper;

    /** 全部字典类型 + 字典项 */
    public List<DictVO> list() {
        List<SysDict> dicts = dictMapper.selectList(
                Wrappers.<SysDict>lambdaQuery().orderByAsc(SysDict::getId));
        List<SysDictItem> items = itemMapper.selectList(
                Wrappers.<SysDictItem>lambdaQuery().orderByAsc(SysDictItem::getSortNo));
        Map<Long, List<SysDictItem>> byDict = items.stream()
                .collect(Collectors.groupingBy(SysDictItem::getDictId));
        return dicts.stream().map(d -> new DictVO(d.getId(), d.getDictType(), d.getDictName(),
                d.getRemark(),
                byDict.getOrDefault(d.getId(), List.of()).stream().map(this::toItemVO).toList()))
                .toList();
    }

    /**
     * 业务表单选项（FR-SYS-07/NFR-EXT-01）：仅返回启用中的字典项（停用项不可被新单据选择），
     * 按排序号升序；供报损原因等业务下拉使用，任意已登录用户可读。
     */
    public List<DictVO.ItemVO> enabledItems(String dictType) {
        SysDict dict = requireDict(dictType);
        return itemMapper.selectList(Wrappers.<SysDictItem>lambdaQuery()
                        .eq(SysDictItem::getDictId, dict.getId())
                        .eq(SysDictItem::getStatus, 1)
                        .orderByAsc(SysDictItem::getSortNo))
                .stream().map(this::toItemVO).toList();
    }

    /**
     * 启用中的字典项值集合（FR-SYS-07）：供业务侧校验枚举合法性（停用项不可被新单据使用）；
     * 字典缺失或整体停用时返回空集，由调用方以内置枚举兜底，避免字典误配置阻断业务。
     */
    public Set<String> enabledValues(String dictType) {
        SysDict dict = dictMapper.selectOne(Wrappers.<SysDict>lambdaQuery()
                .eq(SysDict::getDictType, dictType)
                .eq(SysDict::getStatus, 1));
        if (dict == null) {
            return Set.of();
        }
        return itemMapper.selectList(Wrappers.<SysDictItem>lambdaQuery()
                        .eq(SysDictItem::getDictId, dict.getId())
                        .eq(SysDictItem::getStatus, 1))
                .stream().map(SysDictItem::getValue).collect(Collectors.toSet());
    }

    /**
     * 字典项 值 → 名称 映射（含停用项，FR-SYS-07 历史数据展示口径，GAP-P1-09）：
     * 报表等处按编码回显中文名，历史单据使用的停用项仍能正确显示；
     * 字典不存在时返回空映射，由调用方回退内置标签。
     */
    public Map<String, String> valueLabels(String dictType) {
        SysDict dict = dictMapper.selectOne(Wrappers.<SysDict>lambdaQuery()
                .eq(SysDict::getDictType, dictType));
        if (dict == null) {
            return Map.of();
        }
        return itemMapper.selectList(Wrappers.<SysDictItem>lambdaQuery()
                        .eq(SysDictItem::getDictId, dict.getId())
                        .orderByAsc(SysDictItem::getSortNo))
                .stream().collect(Collectors.toMap(SysDictItem::getValue, SysDictItem::getLabel, (a, b) -> a));
    }

    /** 新增字典项：同一字典下 value 唯一 */
    public void addItem(String dictType, DictItemSaveRequest req, String operator) {
        SysDict dict = requireDict(dictType);
        checkValueUnique(dict.getId(), req.value(), null);
        SysDictItem item = new SysDictItem();
        item.setDictId(dict.getId());
        item.setLabel(req.label());
        item.setValue(req.value());
        item.setSortNo(req.sortNo());
        item.setStatus(req.status());
        item.setRemark(req.remark());
        item.setCreateBy(operator);
        itemMapper.insert(item);
    }

    /** 编辑字典项：value 不可与其他项冲突 */
    public void updateItem(Long itemId, DictItemSaveRequest req, String operator) {
        SysDictItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "字典项不存在");
        }
        checkValueUnique(item.getDictId(), req.value(), itemId);
        item.setLabel(req.label());
        item.setValue(req.value());
        item.setSortNo(req.sortNo());
        item.setStatus(req.status());
        item.setRemark(req.remark());
        item.setUpdateBy(operator);
        itemMapper.updateById(item);
    }

    private SysDict requireDict(String dictType) {
        SysDict dict = dictMapper.selectOne(Wrappers.<SysDict>lambdaQuery()
                .eq(SysDict::getDictType, dictType));
        if (dict == null || dict.getStatus() == null || dict.getStatus() != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "字典类型不存在或已停用");
        }
        return dict;
    }

    private void checkValueUnique(Long dictId, String value, Long excludeItemId) {
        Long count = itemMapper.selectCount(Wrappers.<SysDictItem>lambdaQuery()
                .eq(SysDictItem::getDictId, dictId)
                .eq(SysDictItem::getValue, value)
                .ne(excludeItemId != null, SysDictItem::getId, excludeItemId));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "字典项值已存在：" + value);
        }
    }

    private DictVO.ItemVO toItemVO(SysDictItem item) {
        return new DictVO.ItemVO(item.getId(), item.getLabel(), item.getValue(),
                item.getSortNo(), item.getStatus(), item.getRemark());
    }
}
