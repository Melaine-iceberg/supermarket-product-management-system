package com.smms.common.exception;

import com.smms.common.api.ErrorCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常：携带错误码与用户可读的中文提示（NFR-USE-04）。
 */
@Getter
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
