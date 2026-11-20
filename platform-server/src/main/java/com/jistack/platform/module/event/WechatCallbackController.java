package com.jistack.platform.module.event;

import com.jistack.platform.common.web.BizException;
import com.jistack.platform.wechat.client.WechatApiException;
import com.jistack.platform.wechat.service.AuthorizationService;
import com.jistack.platform.wechat.service.WeChatCallbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 微信开放平台的两个对外入口（不走后台鉴权）：
 * 授权事件/消息接收 URL，以及扫码授权后的浏览器跳转回调。
 */
@Slf4j
@RestController
@RequestMapping("/wx")
@RequiredArgsConstructor
public class WechatCallbackController {

    private final WeChatCallbackService callbackService;
    private final AuthorizationService authorizationService;

    /**
     * 容器健康检查用。以前 HEALTHCHECK 直接打 /wx/callback，每 30 秒就在业务日志里
     * 留下一条与微信无关的探测请求；这个端点只证明服务和映射活着。
     */
    @GetMapping(value = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() {
        return "ok";
    }

    /** 开放平台后台填「消息与事件接收 URL」时先做 GET 校验。 */
    @GetMapping(value = {"/callback", "/callback/{appid}"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String verify(@RequestParam(name = "signature", required = false) String signature,
                         @RequestParam(name = "msg_signature", required = false) String msgSignature,
                         @RequestParam(name = "timestamp", required = false) String timestamp,
                         @RequestParam(name = "nonce", required = false) String nonce,
                         @RequestParam(name = "echostr", required = false) String echoStr) {
        if (timestamp == null || nonce == null) {
            // 微信真正的校验一定带这三个参数；不带参数的 GET 是浏览器直开或监控探测，
            // 用必填 @RequestParam 会变成 500 + 整段栈，把同期要查的错误淹掉。
            log.warn("回调 URL 校验缺少 timestamp/nonce，多半是浏览器或监控直接访问了回调地址");
            return "verify failed: missing timestamp/nonce";
        }
        // 普通模式把签名放在 signature，安全模式放在 msg_signature（与 POST 推送同名），只认一个会恒校验失败
        String provided = signature != null ? signature : msgSignature;
        try {
            return callbackService.verifyUrl(provided, timestamp, nonce, echoStr);
        } catch (Exception ex) {
            log.error("回调 URL 校验失败: {}", ex.toString());
            return "verify failed: " + ex.getMessage();
        }
    }

    /**
     * 票据与授权事件推送、小程序消息代收都落在这里。
     *
     * <p>{@code /callback/{appid}} 对应「消息与事件接收 URL」，微信会把 URL 里的 {@code $APPID$}
     * 换成实际小程序的 appid 再推送。
     */
    @PostMapping(value = {"/callback", "/callback/{appid}"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String push(@RequestParam(name = "msg_signature", required = false) String msgSignature,
                       @RequestParam(name = "signature", required = false) String signature,
                       @RequestParam(name = "timestamp", required = false) String timestamp,
                       @RequestParam(name = "nonce", required = false) String nonce,
                       @RequestBody(required = false) String body) {
        if (timestamp == null || nonce == null) {
            // 微信的推送必带 msg_signature/timestamp/nonce，缺参数的 POST 来自扫描或误配置，
            // 再往下走会在签名拼接里 NPE 变成 500。
            log.warn("回调推送缺少 timestamp/nonce，已忽略（非微信真实推送）");
            return "verify failed: missing timestamp/nonce";
        }
        return callbackService.handlePush(msgSignature != null ? msgSignature : signature, timestamp, nonce, body);
    }

    /**
     * 扫码授权完成后的浏览器回调：{@code ?auth_code=xxx&expires_in=600&state=xxx}。
     * 换到授权信息后跳回前端列表页。
     */
    @GetMapping("/auth/callback")
    public ResponseEntity<Void> authCallback(@RequestParam(name = "auth_code", required = false) String authCode,
                                             @RequestParam(value = "state", required = false) String state) {
        try {
            String appid = authorizationService.confirmByAuthCode(authCode, state);
            return redirectTo("/authorizers", "success", "appid", appid);
        } catch (Exception ex) {
            log.warn("授权回调处理失败: {}", ex.toString());
            return redirectTo("/authorizers", "failed", "message", briefReason(ex));
        }
    }

    /**
     * 跳转地址必须用 queryParam 单独拼：{@code fromPath("/a?b=c")} 会把 {@code ?} 当成路径的一部分
     * 编码成 {@code %3F}，前端路由匹配不到，用户看到的是 404 而不是结果。
     */
    private ResponseEntity<Void> redirectTo(String path, String auth, String paramName, String paramValue) {
        java.net.URI uri = UriComponentsBuilder.fromPath(path)
                .queryParam("auth", auth)
                .queryParam(paramName, paramValue)
                .build()
                .encode()
                .toUri();
        return ResponseEntity.status(302).location(uri).build();
    }

    /** 地址栏里只放人能看懂的原因；SQL、堆栈这类内部细节留在服务端日志。 */
    private String briefReason(Exception ex) {
        String reason;
        if (ex instanceof BizException biz) {
            reason = biz.getMessage();
        } else if (ex instanceof WechatApiException wechat) {
            reason = "微信接口错误 [" + wechat.getErrcode() + "] " + wechat.getErrmsg();
        } else {
            reason = "授权处理失败，请查看服务端日志";
        }
        if (reason == null) {
            return "授权处理失败，请查看服务端日志";
        }
        return reason.length() <= 160 ? reason : reason.substring(0, 160);
    }
}
