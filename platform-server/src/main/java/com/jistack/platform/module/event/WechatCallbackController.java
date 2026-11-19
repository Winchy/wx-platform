package com.jistack.platform.module.event;

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

    /** 开放平台后台填「消息与事件接收 URL」时先做 GET 校验。 */
    @GetMapping(value = {"/callback", "/callback/{appid}"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String verify(@RequestParam(name = "signature", required = false) String signature,
                         @RequestParam("timestamp") String timestamp,
                         @RequestParam("nonce") String nonce,
                         @RequestParam(name = "echostr", required = false) String echoStr) {
        try {
            return callbackService.verifyUrl(signature, timestamp, nonce, echoStr);
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
                       @RequestParam("timestamp") String timestamp,
                       @RequestParam("nonce") String nonce,
                       @RequestBody(required = false) String body) {
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
            return redirectTo("/authorizers?auth=success&appid=" + appid);
        } catch (Exception ex) {
            log.warn("授权回调处理失败: {}", ex.toString());
            return redirectTo("/authorizers?auth=failed&message=" + ex.getMessage());
        }
    }

    private ResponseEntity<Void> redirectTo(String path) {
        java.net.URI uri = UriComponentsBuilder.fromPath(path).build().toUri();
        return ResponseEntity.status(302).location(uri).build();
    }
}
