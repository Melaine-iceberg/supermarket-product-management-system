package com.smms.purchase.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采购单创建请求的 Bean Validation 约束（FR-PUR-01 / BR-PUR-03）。
 *
 * <p>回归背景（DEF-27）：{@code items} 原误用数值约束 {@code @Max}（不适用于 List 类型），
 * Hibernate Validator 在控制器入参校验阶段抛 UnexpectedTypeException → 所有创建请求 500，
 * “新建采购单”功能整体不可用；服务层单测不经过 Bean Validation，故长期未暴露。</p>
 */
class PurchaseSaveRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void rejectsMoreThan200ItemsWithoutValidatorCrash() {
        Set<ConstraintViolation<PurchaseSaveRequest>> violations = validator.validate(validRequest(201));

        assertThat(violations).hasSize(1);
        ConstraintViolation<PurchaseSaveRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("items");
        assertThat(violation.getMessage()).contains("最多 200 行");
    }

    @Test
    void acceptsExactly200Items() {
        Set<ConstraintViolation<PurchaseSaveRequest>> violations = validator.validate(validRequest(200));

        assertThat(violations).isEmpty();
    }

    @Test
    void rejectsEmptyItems() {
        Set<ConstraintViolation<PurchaseSaveRequest>> violations = validator.validate(validRequest(0));

        assertThat(violations).hasSize(1);
        ConstraintViolation<PurchaseSaveRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("items");
        assertThat(violation.getMessage()).isEqualTo("采购明细不能为空");
    }

    private static PurchaseSaveRequest validRequest(int itemCount) {
        PurchaseSaveRequest request = new PurchaseSaveRequest();
        request.setSupplierId(1L);
        List<PurchaseSaveRequest.ItemRequest> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            PurchaseSaveRequest.ItemRequest item = new PurchaseSaveRequest.ItemRequest();
            item.setGoodsId((long) (i + 1));
            item.setQuantity(new BigDecimal("2.000"));
            item.setPrice(new BigDecimal("3.50"));
            items.add(item);
        }
        request.setItems(items);
        return request;
    }
}
