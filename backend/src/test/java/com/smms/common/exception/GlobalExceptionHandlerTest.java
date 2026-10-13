package com.smms.common.exception;

import com.smms.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传超限异常处理（IR-SW-04 / GAP-P1-02）：超限返回 1115 业务提示而非 500 兜底。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("超过 multipart 限制：返回错误码 1115（上传文件超限）")
    void maxUploadSize_returns1115() {
        var response = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(10 * 1024 * 1024L));

        assertThat(response.getCode()).isEqualTo(ErrorCode.UPLOAD_SIZE_EXCEEDED.getCode());
        assertThat(response.getMessage()).contains("10MB");
    }

    @Test
    @DisplayName("其余 multipart 异常：返回 400 参数不合法与可读提示")
    void multipart_returns400() {
        var response = handler.handleMultipart(new MultipartException("boundary broken"));

        assertThat(response.getCode()).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThat(response.getMessage()).contains("文件上传失败");
    }
}
