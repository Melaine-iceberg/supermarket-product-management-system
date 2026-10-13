package com.smms.goods.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.goods.dto.CategoryNode;
import com.smms.goods.dto.CategorySaveRequest;
import com.smms.goods.entity.Category;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.goods.mapper.GoodsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品分类服务（FR-CAT-01~06）：
 * 树形结构最多 3 级（BR-CAT-04）；同级名称唯一（BR-CAT-01）；
 * 不允许环（BR-CAT-02）；有商品关联禁止删除（BR-CAT-03）；
 * 停用级联子孙，启用要求祖先已启用（FR-CAT-04）。
 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    /** 分类层级上限（BR-CAT-04） */
    private static final int MAX_LEVEL = 3;
    private static final long ROOT = 0L;

    private final CategoryMapper categoryMapper;
    private final GoodsMapper goodsMapper;

    // -------------------- 查询 --------------------

    /** 分类树（FR-CAT-01）：节点含子分类汇总的商品数量 */
    public List<CategoryNode> tree() {
        List<Category> all = new ArrayList<>(categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .orderByAsc(Category::getSortNo).orderByAsc(Category::getId)));
        all.sort(Comparator.comparing(Category::getSortNo).thenComparing(Category::getId));
        Map<Long, Long> goodsByCat = countGoodsByCategory();
        Map<Long, List<Category>> byParent = all.stream()
                .collect(Collectors.groupingBy(Category::getParentId));
        return buildChildren(ROOT, byParent, goodsByCat);
    }

    private List<CategoryNode> buildChildren(Long parentId,
                                             Map<Long, List<Category>> byParent,
                                             Map<Long, Long> goodsByCat) {
        List<Category> children = byParent.getOrDefault(parentId, List.of());
        return children.stream()
                .map(c -> CategoryNode.of(c.getId(), c.getParentId(), c.getName(), c.getLevel(),
                        c.getSortNo(), c.getStatus(), goodsByCat.getOrDefault(c.getId(), 0L))
                        .withChildren(buildChildren(c.getId(), byParent, goodsByCat)))
                .toList();
    }

    /**
     * 分类子树 id 集合（含自身，GAP-P0-06）：按物化路径前缀匹配；分类不存在时抛参数错误。
     * 供收银台分类浏览等跨模块只读查询复用。
     */
    public List<Long> resolveTreeIds(Long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "所选分类不存在");
        }
        String prefix = category.getPath() + "/";
        List<Long> ids = new ArrayList<>();
        ids.add(categoryId);
        categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .select(Category::getId, Category::getPath))
                .stream()
                .filter(c -> c.getPath().startsWith(prefix))
                .forEach(c -> ids.add(c.getId()));
        return ids;
    }

    /** 各分类直接挂载的商品数（BR-CAT-04：商品只挂叶子分类） */
    private Map<Long, Long> countGoodsByCategory() {
        QueryWrapper<com.smms.goods.entity.Goods> qw = new QueryWrapper<>();
        qw.select("category_id", "COUNT(*) AS cnt").groupBy("category_id");
        return goodsMapper.selectMaps(qw).stream()
                .collect(Collectors.toMap(
                        m -> ((Number) m.get("category_id")).longValue(),
                        m -> ((Number) m.get("cnt")).longValue()));
    }

    // -------------------- 新增 --------------------

    /** 新增分类（FR-CAT-02） */
    @Transactional(rollbackFor = Exception.class)
    public void create(CategorySaveRequest req, String operator) {
        String name = checkedName(req.name());
        Category parent = requireParent(req.parentId());
        checkParentUsable(parent);
        checkSiblingNameUnique(req.parentId(), name, null);

        Category cat = new Category();
        cat.setParentId(req.parentId());
        cat.setName(name);
        cat.setSortNo(req.sortNo());
        cat.setStatus(req.status());
        cat.setLevel(parent == null ? 1 : parent.getLevel() + 1);
        cat.setCreateBy(operator);
        categoryMapper.insert(cat);
        // 物化路径依赖自增 ID，插入后回填
        cat.setPath(parent == null ? String.valueOf(cat.getId()) : parent.getPath() + "/" + cat.getId());
        categoryMapper.updateById(cat);
    }

    // -------------------- 编辑 --------------------

    /** 编辑分类（FR-CAT-03）：改名/排序/状态；调整上级须校验环、层级、重名 */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, CategorySaveRequest req, String operator) {
        Category cat = requireCategory(id);
        String name = checkedName(req.name());

        boolean parentChanged = !cat.getParentId().equals(req.parentId());
        if (parentChanged) {
            Category newParent = requireParent(req.parentId());
            checkNoCycle(cat, newParent);
            checkParentUsable(newParent);
            checkLevelNotExceed(cat, newParent);
            checkSiblingNameUnique(req.parentId(), name, id);
        } else {
            checkSiblingNameUnique(cat.getParentId(), name, id);
        }

        // 状态变更走统一口径：停用级联子孙 / 启用需祖先已启用（FR-CAT-04）
        if (!cat.getStatus().equals(req.status())) {
            changeStatus(cat, req.status(), operator);
        }

        if (parentChanged) {
            moveSubtree(cat, req.parentId());
        }
        cat.setName(name);
        cat.setSortNo(req.sortNo());
        cat.setStatus(req.status());
        cat.setUpdateBy(operator);
        categoryMapper.updateById(cat);
    }

    // -------------------- 停用/启用 --------------------

    /** 停用/启用（FR-CAT-04）：停用级联子孙；启用要求祖先已启用并级联启用子孙 */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, int status, String operator) {
        Category cat = requireCategory(id);
        changeStatus(cat, status, operator);
    }

    private void changeStatus(Category cat, int status, String operator) {
        if (cat.getStatus() == status) {
            return;
        }
        List<Category> all = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .select(Category::getId, Category::getParentId, Category::getName,
                        Category::getPath, Category::getStatus));
        if (status == 1) {
            checkAncestorsEnabled(cat, all);
        }
        // 停用/启用均级联其下所有子孙，保证子树状态一致
        String prefix = cat.getPath() + "/";
        for (Category c : all) {
            boolean descendant = c.getPath().startsWith(prefix);
            if (c.getId().equals(cat.getId()) || descendant) {
                c.setStatus(status);
                c.setUpdateBy(operator);
                categoryMapper.updateById(c);
            }
        }
    }

    /** 启用时校验所有祖先均已启用（否则形成"父停用、子启用"的矛盾状态） */
    private void checkAncestorsEnabled(Category cat, List<Category> all) {
        Map<Long, Category> byId = all.stream()
                .collect(Collectors.toMap(Category::getId, c -> c));
        Long parentId = cat.getParentId();
        while (parentId != null && parentId != ROOT) {
            Category parent = byId.get(parentId);
            if (parent == null) {
                break;
            }
            if (parent.getStatus() != 1) {
                throw new BusinessException(ErrorCode.CAT_ANCESTOR_DISABLED,
                        "上级分类【" + parent.getName() + "】已停用，请先启用上级分类");
            }
            parentId = parent.getParentId();
        }
    }

    // -------------------- 删除 --------------------

    /** 删除分类（FR-CAT-05，BR-CAT-03）：仅允许删除无子分类且无商品关联的分类 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireCategory(id);
        Long childCount = categoryMapper.selectCount(Wrappers.<Category>lambdaQuery()
                .eq(Category::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BusinessException(ErrorCode.CAT_HAS_CHILDREN,
                    "该分类下存在 " + childCount + " 个子分类，请先删除或移动子分类");
        }
        Long goodsCount = goodsMapper.selectCount(Wrappers.<com.smms.goods.entity.Goods>lambdaQuery()
                .eq(com.smms.goods.entity.Goods::getCategoryId, id));
        if (goodsCount != null && goodsCount > 0) {
            throw new BusinessException(ErrorCode.CAT_HAS_GOODS,
                    "该分类下存在 " + goodsCount + " 个商品，禁止删除，请改用停用");
        }
        categoryMapper.deleteById(id);
    }

    // -------------------- 私有校验 --------------------

    /** 名称不可含前后空格（BR-CAT-01） */
    private String checkedName(String name) {
        if (!name.equals(name.trim())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "分类名称不能包含前后空格");
        }
        return name;
    }

    /** 上级分类加载：root(0) 返回 null，否则须存在 */
    private Category requireParent(Long parentId) {
        if (parentId == null || parentId == ROOT) {
            return null;
        }
        return requireCategory(parentId);
    }

    /** 作为上级的可用性：启用状态 + 层级不超上限（BR-CAT-04） */
    private void checkParentUsable(Category parent) {
        if (parent == null) {
            return;
        }
        if (parent.getStatus() != 1) {
            throw new BusinessException(ErrorCode.CAT_PARENT_DISABLED,
                    "上级分类【" + parent.getName() + "】已停用，请先启用");
        }
        if (parent.getLevel() + 1 > MAX_LEVEL) {
            throw new BusinessException(ErrorCode.CAT_LEVEL_EXCEED,
                    "上级【" + parent.getName() + "】已是第3级，无法再建下级分类");
        }
    }

    private Category requireCategory(Long id) {
        Category cat = categoryMapper.selectById(id);
        if (cat == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分类不存在或已被删除");
        }
        return cat;
    }

    /** 同父下名称唯一（BR-CAT-01，DR-C-01 数据库唯一键兜底） */
    private void checkSiblingNameUnique(Long parentId, String name, Long excludeId) {
        Long count = categoryMapper.selectCount(Wrappers.<Category>lambdaQuery()
                .eq(Category::getParentId, parentId)
                .eq(Category::getName, name)
                .ne(excludeId != null, Category::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.CAT_NAME_EXISTS,
                    "同级下已存在同名分类【" + name + "】");
        }
    }

    /** 环校验（BR-CAT-02）：新上级不能是自身或其子孙 */
    private void checkNoCycle(Category cat, Category newParent) {
        if (newParent == null) {
            return;
        }
        if (newParent.getId().equals(cat.getId())
                || newParent.getPath().startsWith(cat.getPath() + "/")) {
            throw new BusinessException(ErrorCode.CAT_PARENT_INVALID,
                    "上级分类不能是自身或其子孙分类【" + newParent.getName() + "】");
        }
    }

    /** 移动层级校验：新层级 + 子树深度 ≤ 3（BR-CAT-04） */
    private void checkLevelNotExceed(Category cat, Category newParent) {
        int newLevel = newParent == null ? 1 : newParent.getLevel() + 1;
        List<Category> all = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .select(Category::getId, Category::getPath, Category::getLevel));
        int subtreeDepth = all.stream()
                .filter(c -> c.getPath().startsWith(cat.getPath() + "/") || c.getId().equals(cat.getId()))
                .mapToInt(c -> c.getLevel() - cat.getLevel() + 1)
                .max().orElse(1);
        if (newLevel + subtreeDepth - 1 > MAX_LEVEL) {
            throw new BusinessException(ErrorCode.CAT_LEVEL_EXCEED,
                    "移动后子树层级将超过" + MAX_LEVEL + "级（当前子树深度 " + subtreeDepth + " 级）");
        }
    }

    /** 跨级移动（FR-CAT-06）：同步维护自身与子孙的 path / level */
    private void moveSubtree(Category cat, Long newParentId) {
        List<Category> all = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .select(Category::getId, Category::getParentId, Category::getPath, Category::getLevel));
        Map<Long, Category> byId = new HashMap<>();
        all.forEach(c -> byId.put(c.getId(), c));
        Category newParent = newParentId == ROOT ? null : byId.get(newParentId);

        String oldPath = cat.getPath();
        String newPath = newParent == null
                ? String.valueOf(cat.getId())
                : newParent.getPath() + "/" + cat.getId();
        int newLevel = newParent == null ? 1 : newParent.getLevel() + 1;
        int delta = newLevel - cat.getLevel();

        cat.setParentId(newParentId);
        cat.setPath(newPath);
        cat.setLevel(newLevel);
        categoryMapper.updateById(cat);
        // 子孙节点：前缀替换 + 层级平移
        for (Category c : all) {
            if (c.getPath().startsWith(oldPath + "/")) {
                c.setPath(newPath + c.getPath().substring(oldPath.length()));
                c.setLevel(c.getLevel() + delta);
                categoryMapper.updateById(c);
            }
        }
    }
}
