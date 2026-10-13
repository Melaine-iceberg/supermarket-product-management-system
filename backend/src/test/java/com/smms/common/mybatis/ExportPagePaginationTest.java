package com.smms.common.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GAP-P0-01 回归（FR-RPT-08 / IR-COM-04）：
 * 普通 API 分页仍受全局单页上限 500 条保护；
 * 导出页（{@link ExportPage}）豁免该上限，使 export_max_rows+1 的超限判定（checkLimit）真正可达。
 *
 * <p>直接驱动 MyBatis-Plus {@code PaginationInnerInterceptor#beforeQuery}（无数据库），
 * 断言截断行为发生在分页插件层（修复前导出页在此被静默截为 500）。
 */
class ExportPagePaginationTest {

    @Test
    @DisplayName("普通分页请求 1000 条被全局上限截为 500 条（IR-COM-04 保护仍生效）")
    void normalPageStillCappedByGlobalLimit() {
        IPage<Object> page = new Page<>(1, 1000, false);

        beforeQuery(page);

        assertThat(page.getSize()).isEqualTo(500);
    }

    @Test
    @DisplayName("导出页请求 50001 条不被截断，供 checkLimit 判定超限（导出修复核心断言）")
    void exportPageBypassesGlobalLimit() {
        IPage<Object> page = new ExportPage<>(50001);

        beforeQuery(page);

        assertThat(page.getSize()).isEqualTo(50001);
    }

    private static void beforeQuery(IPage<?> page) {
        PaginationInnerInterceptor interceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        interceptor.setMaxLimit(500L); // 与 MybatisPlusConfig 一致（IR-COM-04）

        MappedStatement ms = mock(MappedStatement.class);
        when(ms.getConfiguration()).thenReturn(new MybatisConfiguration());
        // parameterMappings 必须可变：分页方言会向其中追加 LIMIT 占位参数
        List<ParameterMapping> mappings = new ArrayList<>();
        BoundSql boundSql = new BoundSql(ms.getConfiguration(), "SELECT 1", mappings, null);

        interceptor.beforeQuery(mock(Executor.class), ms, page, null,
                Executor.NO_RESULT_HANDLER, boundSql);
    }
}
