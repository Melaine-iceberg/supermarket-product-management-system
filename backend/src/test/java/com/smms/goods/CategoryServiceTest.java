package com.smms.goods;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.exception.BusinessException;
import com.smms.goods.dto.CategoryNode;
import com.smms.goods.dto.CategorySaveRequest;
import com.smms.goods.entity.Category;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.service.CategoryService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分类服务单元测试：覆盖 BR-CAT-01（同级重名）、BR-CAT-02（环校验）、
 * BR-CAT-03（有商品禁删）、BR-CAT-04（层级上限）与停用级联（FR-CAT-04）。
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private GoodsMapper goodsMapper;

    private CategoryService service;

    @BeforeEach
    void setUp() {
        // 纯单测环境无 MyBatis 容器，手工注册实体元数据供 Lambda 缓存解析
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Category.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        service = new CategoryService(categoryMapper, goodsMapper);
    }

    // ---------- 造数 ----------

    private Category cat(long id, long parentId, String name, int level, String path, int status) {
        Category c = new Category();
        c.setId(id);
        c.setParentId(parentId);
        c.setName(name);
        c.setLevel(level);
        c.setPath(path);
        c.setSortNo(1);
        c.setStatus(status);
        return c;
    }

    private CategorySaveRequest req(String name, long parentId, int status) {
        return new CategorySaveRequest(name, parentId, 1, status);
    }

    // ---------- 新增 ----------

    @Test
    @DisplayName("新增一级分类：回填物化路径")
    void createRoot_fillsPath() {
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.insert(any(Category.class))).thenAnswer(inv -> {
            inv.getArgument(0, Category.class).setId(9L);
            return 1;
        });

        service.create(req("零食", 0L, 1), "admin");

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryMapper).updateById(captor.capture());
        assertThat(captor.getValue().getPath()).isEqualTo("9");
        assertThat(captor.getValue().getLevel()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-CAT-01：同级下名称重复拒绝新增")
    void create_duplicateSiblingName_rejected() {
        when(categoryMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(req("食品", 0L, 1), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同级下已存在同名分类");
        verify(categoryMapper, never()).insert(any(Category.class));
    }

    @Test
    @DisplayName("BR-CAT-04：上级已是第3级时拒绝新增下级")
    void create_parentAtLevel3_rejected() {
        when(categoryMapper.selectById(5L)).thenReturn(cat(5, 1, "三级", 3, "1/2/5", 1));

        assertThatThrownBy(() -> service.create(req("四级", 5L, 1), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("第3级");
    }

    @Test
    @DisplayName("FR-CAT-04：上级分类已停用时拒绝新增子分类")
    void create_parentDisabled_rejected() {
        when(categoryMapper.selectById(5L)).thenReturn(cat(5, 0, "停用分类", 1, "5", 0));

        assertThatThrownBy(() -> service.create(req("子分类", 5L, 1), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上级分类");
    }

    @Test
    @DisplayName("BR-CAT-01：名称含前后空格拒绝")
    void create_nameWithSpaces_rejected() {
        assertThatThrownBy(() -> service.create(req(" 零食 ", 0L, 1), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("前后空格");
    }

    // ---------- 编辑 ----------

    @Test
    @DisplayName("BR-CAT-02：上级改为自身子孙分类拒绝")
    void update_moveToDescendant_rejected() {
        Category node = cat(2, 1, "中类", 2, "1/2", 1);
        Category descendant = cat(5, 2, "小类", 3, "1/2/5", 1);
        when(categoryMapper.selectById(2L)).thenReturn(node);
        when(categoryMapper.selectById(5L)).thenReturn(descendant);

        assertThatThrownBy(() -> service.update(2L, req("中类", 5L, 1), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能是自身或其子孙");
    }

    @Test
    @DisplayName("BR-CAT-02：上级改为自身拒绝")
    void update_moveToSelf_rejected() {
        when(categoryMapper.selectById(2L)).thenReturn(cat(2, 0, "大类", 1, "2", 1));

        assertThatThrownBy(() -> service.update(2L, req("大类", 2L, 1), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能是自身或其子孙");
    }

    @Test
    @DisplayName("BR-CAT-04：移动后子树层级超3级拒绝")
    void update_moveCausesLevelOverflow_rejected() {
        // 节点 2（level1，子树深 2：2 → 2/5）移动到 level2 节点 7 下 → 新层级 3 + 子树深 2 - 1 = 4 级，拒绝
        when(categoryMapper.selectById(2L)).thenReturn(cat(2, 0, "大类", 1, "2", 1));
        when(categoryMapper.selectById(7L)).thenReturn(cat(7, 1, "目标", 2, "1/7", 1));
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                cat(2, 0, "大类", 1, "2", 1),
                cat(5, 2, "小类", 2, "2/5", 1)));

        assertThatThrownBy(() -> service.update(2L, req("大类", 7L, 1), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过3级");
    }

    @Test
    @DisplayName("跨级移动：自身与子孙的 path/level 同步维护")
    void update_moveSubtree_updatesPaths() {
        // 节点 2（level2，子树深 2：1/2 → 1/2/5）移动到根 → 新层级 1，整棵子树上移一级
        Category node = cat(2, 1, "中类", 2, "1/2", 1);
        Category child = cat(5, 2, "小类", 3, "1/2/5", 1);
        when(categoryMapper.selectById(2L)).thenReturn(node);
        // 第一次 selectList：层级校验；第二次：移动子树
        when(categoryMapper.selectList(any())).thenReturn(List.of(node, child), List.of(node, child));

        service.update(2L, req("中类", 0L, 1), "admin");

        assertThat(node.getPath()).isEqualTo("2");
        assertThat(node.getLevel()).isEqualTo(1);
        assertThat(node.getParentId()).isEqualTo(0L);
        assertThat(child.getPath()).isEqualTo("2/5");
        assertThat(child.getLevel()).isEqualTo(2);
    }

    // ---------- 停用/启用 ----------

    @Test
    @DisplayName("FR-CAT-04：停用级联所有子孙节点")
    void disable_cascadesToDescendants() {
        Category root = cat(1, 0, "食品", 1, "1", 1);
        Category mid = cat(2, 1, "中类", 2, "1/2", 1);
        Category leaf = cat(5, 2, "小类", 3, "1/2/5", 1);
        when(categoryMapper.selectById(1L)).thenReturn(root);
        when(categoryMapper.selectList(any())).thenReturn(List.of(root, mid, leaf));

        service.changeStatus(1L, 0, "admin");

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryMapper, org.mockito.Mockito.times(3)).updateById(captor.capture());
        assertThat(captor.getAllValues()).extracting(Category::getId)
                .containsExactlyInAnyOrder(1L, 2L, 5L);
        assertThat(captor.getAllValues()).allSatisfy(c -> assertThat(c.getStatus()).isZero());
    }

    @Test
    @DisplayName("FR-CAT-04：上级停用时启用子分类被拒绝")
    void enable_ancestorDisabled_rejected() {
        Category mid = cat(2, 1, "中类", 2, "1/2", 0);
        Category root = cat(1, 0, "食品", 1, "1", 0);
        when(categoryMapper.selectById(2L)).thenReturn(mid);
        when(categoryMapper.selectList(any())).thenReturn(List.of(root, mid));

        assertThatThrownBy(() -> service.changeStatus(2L, 1, "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先启用上级分类");
    }

    // ---------- 删除 ----------

    @Test
    @DisplayName("BR-CAT-03：存在子分类时禁止删除")
    void delete_hasChildren_rejected() {
        when(categoryMapper.selectById(1L)).thenReturn(cat(1, 0, "食品", 1, "1", 1));
        when(categoryMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("子分类");
        verify(categoryMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("BR-CAT-03：存在商品关联时禁止删除（提示改用停用）")
    void delete_hasGoods_rejected() {
        when(categoryMapper.selectById(1L)).thenReturn(cat(1, 0, "食品", 1, "1", 1));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(goodsMapper.selectCount(any())).thenReturn(23L);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("23")
                .hasMessageContaining("改用停用");
        verify(categoryMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("无子分类且无商品时允许删除")
    void delete_leafWithoutGoods_ok() {
        when(categoryMapper.selectById(1L)).thenReturn(cat(1, 0, "食品", 1, "1", 1));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(goodsMapper.selectCount(any())).thenReturn(0L);

        service.delete(1L);

        verify(categoryMapper).deleteById(1L);
    }

    // ---------- 树查询 ----------

    @Test
    @DisplayName("FR-CAT-01：树节点商品数量按子树汇总，叶子标记正确")
    void tree_aggregatesGoodsCount() {
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                cat(1, 0, "食品", 1, "1", 1),
                cat(2, 1, "饮料", 2, "1/2", 1),
                cat(3, 2, "碳酸饮料", 3, "1/2/3", 1)));
        // 模拟 selectMaps 分组计数：分类3挂3个商品、分类1直挂1个（防御性兼容非叶子挂载）
        when(goodsMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("category_id", 3L, "cnt", 3L),
                Map.of("category_id", 1L, "cnt", 1L)));

        List<CategoryNode> tree = service.tree();

        assertThat(tree).hasSize(1);
        CategoryNode food = tree.get(0);
        assertThat(food.goodsCount()).isEqualTo(4L);      // 1 + 3 汇总
        assertThat(food.leaf()).isFalse();
        assertThat(food.children()).hasSize(1);
        CategoryNode soda = food.children().get(0).children().get(0);
        assertThat(soda.goodsCount()).isEqualTo(3L);
        assertThat(soda.leaf()).isTrue();
    }

    // ---------- 兼容性兜底 ----------

    @Test
    @DisplayName("分类不存在时统一返回 NOT_FOUND")
    void requireCategory_notFound() {
        when(categoryMapper.selectById(eq(99L))).thenReturn(null);
        lenient().when(categoryMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.update(99L, req("任意", 0L, 1), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分类不存在");
    }
}
