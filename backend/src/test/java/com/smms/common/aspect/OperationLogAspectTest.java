package com.smms.common.aspect;

import com.smms.common.annotation.OpLog;
import com.smms.common.api.ApiResponse;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.service.OperationLogService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 操作日志切面测试（GAP-P2-03）：目标对象支持 #ret 返回值 SpEL 解析
 * （单据号在返回值中生成时，日志须记录真实单号）。
 */
class OperationLogAspectTest {

    private final OperationLogService logService = mock(OperationLogService.class);
    private final OperationLogAspect aspect = new OperationLogAspect(logService, new ObjectMapper());

    /** 模拟收银结算控制器：返回 ApiResponse<VO("XS2026...")> */
    static class DemoController {
        @OpLog(module = "销售管理", action = "收银结算", target = "#ret?.data.orderNo")
        public ApiResponse<DemoVO> checkout() {
            return ApiResponse.ok(new DemoVO("XS20261013001"));
        }
    }

    record DemoVO(String orderNo) {
    }

    @Test
    void shouldResolveTargetFromReturnValue() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new DemoController());
        factory.addAspect(aspect);
        DemoController proxy = factory.getProxy();

        ApiResponse<DemoVO> response = proxy.checkout();
        assertThat(response.getData().orderNo()).isEqualTo("XS20261013001");

        org.mockito.ArgumentCaptor<OperationLog> captor =
                org.mockito.ArgumentCaptor.forClass(OperationLog.class);
        verify(logService).recordAsync(captor.capture());
        assertThat(captor.getValue().getTarget()).isEqualTo("XS20261013001");
        assertThat(captor.getValue().getResult()).isEqualTo(1);
    }

    @Test
    void shouldRecordFailureWhenMethodThrows() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new FailingController());
        factory.addAspect(aspect);
        FailingController proxy = factory.getProxy();

        try {
            proxy.checkout();
        } catch (IllegalStateException expected) {
            // 业务异常按预期抛出
        }

        org.mockito.ArgumentCaptor<OperationLog> captor =
                org.mockito.ArgumentCaptor.forClass(OperationLog.class);
        verify(logService).recordAsync(captor.capture());
        // 失败时无返回值，#ret 安全导航解析为 null，不抛二次异常
        assertThat(captor.getValue().getTarget()).isNull();
        assertThat(captor.getValue().getResult()).isEqualTo(0);
    }

    static class FailingController {
        @OpLog(module = "销售管理", action = "收银结算", target = "#ret?.data.orderNo")
        public ApiResponse<DemoVO> checkout() {
            throw new IllegalStateException("库存不足");
        }
    }
}
