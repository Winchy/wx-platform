package com.jistack.platform.common.web;

import com.jistack.platform.wechat.client.WechatApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

@Slf4j
@RestControllerAdvice(basePackages = "com.jistack.platform.module")
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(WechatApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleWechat(WechatApiException ex) {
        log.warn("微信接口调用失败 errcode={} errmsg={} api={}", ex.getErrcode(), ex.getErrmsg(), ex.getApiPath());
        return ResponseEntity.ok(ApiResponse.fail(ex.getErrcode(), "微信接口错误[" + ex.getErrcode() + "]: " + ex.getErrmsg()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception ex) {
        String message = ex instanceof MethodArgumentNotValidException e
                ? e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).findFirst().orElse("参数校验失败")
                : "参数校验失败";
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, message));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleWechatTransport(ResourceAccessException ex) {
        log.error("微信服务器连接中断", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.fail(502,
                "与微信服务器的连接中断：" + ex.getMessage() + "（上传中断通常是图片或多媒体文件超出微信限制，请先压缩后重试）"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "服务器内部错误: " + ex.getMessage()));
    }
}
