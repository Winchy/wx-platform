package com.jistack.platform.common.web;

import com.jistack.platform.wechat.client.WechatApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice(basePackages = "com.jistack.platform.module")
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex, HttpServletRequest request) {
        HttpStatus status = switch (ex.getCode()) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        // 以前业务异常一行日志都不留：界面有中文原因、日志空白，线上排查只能靠猜。路径不含 query，不会带出 ticket。
        log.warn("业务异常 {} {} code={} message={}",
                request.getMethod(), request.getRequestURI(), ex.getCode(), ex.getMessage());
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

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex,
                                                                HttpServletRequest request) {
        // 回调地址被监控或浏览器不带参数探测时就会撞到这里；以前落进 handleOther 打整段栈，
        // 反而把真正要查的错误淹了。
        log.warn("缺少必填参数 {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getParameterName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, "缺少必填参数：" + ex.getParameterName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                                 HttpServletRequest request) {
        // 免鉴权的登录/取码接口会被爬虫与监控空 body、半截 JSON 地探测；
        // 落到 handleOther 就是整段栈 + 500，把真正的故障淹掉，所以按「客户端没写对请求体」处理。
        log.warn("请求体缺失或格式错误 {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, "请求体缺失或不是合法的 JSON"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        // 界面请求了后端没有的路径，多半是 api 与 web 镜像版本不一致（改了前端没重建后端）。
        log.warn("接口不存在 {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(404, "后端没有这个接口：" + request.getRequestURI() + "（请确认 api 镜像已重新构建）"));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleWechatTransport(ResourceAccessException ex) {
        log.error("微信服务器连接中断", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.fail(502,
                "与微信服务器的连接中断：" + ex.getMessage()
                        + "（上传中断多为图片超出微信限制，或 multipart 传输方式被微信拒绝；下载中断多为服务器到微信域名的出口网络不通）"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "服务器内部错误: " + ex.getMessage()));
    }
}
