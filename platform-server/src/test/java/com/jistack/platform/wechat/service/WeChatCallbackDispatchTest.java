package com.jistack.platform.wechat.service;

import com.jistack.platform.module.event.EventLog;
import com.jistack.platform.module.event.EventLogMapper;
import com.jistack.platform.module.system.PlatformConfig;
import com.jistack.platform.module.system.PlatformConfigMapper;
import com.jistack.platform.module.system.PlatformConfigService;
import com.jistack.platform.wechat.crypto.WeChatMsgCrypt;
import com.jistack.platform.wechat.crypto.XmlCodec;
import com.jistack.platform.wechat.token.ComponentTokenService;
import com.jistack.platform.wechat.token.WechatTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 回调入口的分发：整站的输入只有这一个 URL，走错一步就是「ticket 收不到、授权码没换到」。
 * 这里用真实的 WeChatMsgCrypt 造安全模式报文（加解密本身另有闭环比对），
 * 只验签名之后的分发、落档与「无论如何都回 success」这几条约定。
 */
@DisplayName("微信回调：安全模式推送的分发与落档")
class WeChatCallbackDispatchTest {

    private static final String TOKEN = "e2e-message-token-0123456789ab";
    private static final String COMPONENT_APPID = "wxcomponent0000001";
    private static final String AUTHORIZER_APPID = "wxauthorizer000001";
    private static final String AES_KEY = aesKey();

    private static final WeChatMsgCrypt CRYPT = new WeChatMsgCrypt(TOKEN, AES_KEY, COMPONENT_APPID);

    private PlatformConfigService configService;
    private PlatformConfigMapper configMapper;
    private ComponentTokenService componentTokenService;
    private WechatTokenStore tokenStore;
    private AuthorizerWriter authorizerWriter;
    private EventLogMapper eventLogMapper;
    private WeChatCallbackService service;
    private PlatformConfig configRow;

    private static String aesKey() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 7 + 3);
        }
        return Base64.getEncoder().encodeToString(raw).replace("=", "");
    }

    @BeforeEach
    void setUp() {
        configService = mock(PlatformConfigService.class);
        when(configService.crypt()).thenReturn(CRYPT);
        configMapper = mock(PlatformConfigMapper.class);
        configRow = new PlatformConfig();
        when(configMapper.selectById(PlatformConfig.SINGLETON_ID)).thenReturn(configRow);
        componentTokenService = mock(ComponentTokenService.class);
        tokenStore = mock(WechatTokenStore.class);
        authorizerWriter = mock(AuthorizerWriter.class);
        when(authorizerWriter.applyAuthorizedEvent(AUTHORIZER_APPID, "AUTH_CODE_1")).thenReturn(AUTHORIZER_APPID);
        eventLogMapper = mock(EventLogMapper.class);

        service = new WeChatCallbackService(configService, configMapper, componentTokenService,
                tokenStore, authorizerWriter, eventLogMapper);
    }

    /** 返回 {timestamp, nonce, msgSignature, body}，body 是微信推来的安全模式包体。 */
    private String[] signedPush(String plainMessage, String toUserName) {
        String encrypted = XmlCodec.extractEncrypt(CRYPT.encryptToXml(plainMessage, toUserName));
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = "nonce-" + System.nanoTime();
        Map<String, String> envelope = new LinkedHashMap<>();
        envelope.put("ToUserName", toUserName);
        envelope.put("Encrypt", encrypted);
        envelope.put("EncryptType", "aes");
        return new String[]{timestamp, nonce, CRYPT.computeMsgSignature(timestamp, nonce, encrypted),
                XmlCodec.build(envelope)};
    }

    private String handle(String plainMessage) {
        String[] push = signedPush(plainMessage, COMPONENT_APPID);
        return service.handlePush(push[2], push[0], push[1], push[3]);
    }

    private EventLog logged() {
        ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("component_verify_ticket：落库、通知令牌层、刷新健康检查时间戳")
    void ticketPushUpdatesEveryLayer() {
        String plain = "<xml><AppId>" + COMPONENT_APPID + "</AppId><CreateTime>1763000000</CreateTime>"
                + "<InfoType>component_verify_ticket</InfoType>"
                + "<ComponentVerifyTicket>TICKET@@@abc123</ComponentVerifyTicket></xml>";

        assertThat(handle(plain)).isEqualTo("success");

        verify(tokenStore).saveTicket(COMPONENT_APPID, "TICKET@@@abc123");
        verify(componentTokenService).onTicketReceived(COMPONENT_APPID, "TICKET@@@abc123");
        assertThat(configRow.getTicketReceivedAt()).isNotNull();
        verify(configMapper).updateById(configRow);

        EventLog entry = logged();
        assertThat(entry.getInfoType()).isEqualTo("component_verify_ticket");
        assertThat(entry.getAppid()).isEqualTo(COMPONENT_APPID);
        assertThat(entry.getBodyType()).isEqualTo("XML");
        assertThat(entry.getSignatureOk()).isEqualTo(1);
        assertThat(entry.getHandled()).isEqualTo(1);
        assertThat(entry.getErrorMessage()).isNull();
        assertThat(entry.getDecryptedBody()).contains("ComponentVerifyTicket");
    }

    @Test
    @DisplayName("内层缺 AppId 时用包体 ToUserName 归属票据，不能把 key 存成 null")
    void ticketFallsBackToEnvelopeToUserName() {
        String plain = "<xml><CreateTime>1763000000</CreateTime>"
                + "<InfoType>component_verify_ticket</InfoType>"
                + "<ComponentVerifyTicket>TICKET@@@fallback</ComponentVerifyTicket></xml>";

        assertThat(handle(plain)).isEqualTo("success");

        verify(tokenStore).saveTicket(COMPONENT_APPID, "TICKET@@@fallback");
        assertThat(logged().getAppid()).isEqualTo(COMPONENT_APPID);
    }

    @Test
    @DisplayName("票据包体缺字段时不静默吞掉，事件表留下错误原因")
    void ticketWithoutField() {
        String plain = "<xml><AppId>" + COMPONENT_APPID + "</AppId>"
                + "<InfoType>component_verify_ticket</InfoType></xml>";

        assertThat(handle(plain)).isEqualTo("success");

        verify(tokenStore, org.mockito.Mockito.never()).saveTicket(any(), any());
        EventLog entry = logged();
        assertThat(entry.getHandled()).isZero();
        assertThat(entry.getErrorMessage()).contains("ComponentVerifyTicket");
    }

    @Test
    @DisplayName("authorized/updateauthorized 用 AuthorizationCode 换授权")
    void authorizedEventExchangesCode() {
        String plain = "<xml><AppId>" + COMPONENT_APPID + "</AppId><InfoType>authorized</InfoType>"
                + "<AuthorizerAppid>" + AUTHORIZER_APPID + "</AuthorizerAppid>"
                + "<AuthorizationCode>AUTH_CODE_1</AuthorizationCode></xml>";

        assertThat(handle(plain)).isEqualTo("success");

        verify(authorizerWriter).applyAuthorizedEvent(AUTHORIZER_APPID, "AUTH_CODE_1");
        assertThat(logged().getHandled()).isEqualTo(1);
    }

    @Test
    @DisplayName("unauthorized 只标记解除，不去换码")
    void unauthorizedEventMarksOnly() {
        String plain = "<xml><AppId>" + COMPONENT_APPID + "</AppId><InfoType>unauthorized</InfoType>"
                + "<AuthorizerAppid>" + AUTHORIZER_APPID + "</AuthorizerAppid></xml>";

        assertThat(handle(plain)).isEqualTo("success");

        verify(authorizerWriter).markUnauthorized(AUTHORIZER_APPID);
        verify(authorizerWriter, org.mockito.Mockito.never())
                .applyAuthorizedEvent(eq(AUTHORIZER_APPID), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("小程序侧消息类型不处理，但要留档并回 success")
    void unknownInfoTypeIsArchived() {
        String plain = "<xml><AppId>" + AUTHORIZER_APPID + "</AppId><MsgType>event</MsgType>"
                + "<InfoType>user_enter_temporal_feedback</InfoType></xml>";

        assertThat(handle(plain)).isEqualTo("success");

        verifyNoInteractions(tokenStore, authorizerWriter);
        EventLog entry = logged();
        assertThat(entry.getHandled()).isEqualTo(1);
        assertThat(entry.getInfoType()).isEqualTo("user_enter_temporal_feedback");
    }

    @Test
    @DisplayName("签名校验失败：忽略包体、回 success 避免微信无限重试")
    void badSignatureIsIgnored() {
        String[] push = signedPush("<xml><InfoType>component_verify_ticket</InfoType></xml>", COMPONENT_APPID);

        String response = service.handlePush("0000000000000000000000000000000000000000",
                push[0], push[1], push[3]);

        assertThat(response).isEqualTo("success");
        verifyNoInteractions(tokenStore, authorizerWriter, componentTokenService);
        EventLog entry = logged();
        assertThat(entry.getSignatureOk()).isZero();
        assertThat(entry.getHandled()).isZero();
        assertThat(entry.getErrorMessage()).contains("msg_signature");
    }

    @Test
    @DisplayName("未开启安全模式的包体给出明确原因，而不是 NPE")
    void bodyWithoutEncrypt() {
        String[] push = signedPush("ignored", COMPONENT_APPID);

        assertThat(service.handlePush(push[2], push[0], push[1], "<xml><Foo>bar</Foo></xml>"))
                .isEqualTo("success");

        EventLog entry = logged();
        assertThat(entry.getErrorMessage()).contains("安全模式");
        assertThat(entry.getHandled()).isZero();
    }

    @Test
    @DisplayName("事件表写不进去也不能把异常抛给微信")
    void eventLogFailureIsContained() {
        when(eventLogMapper.insert(any(EventLog.class))).thenThrow(new IllegalStateException("库挂了"));

        assertThat(handle("<xml><AppId>" + COMPONENT_APPID + "</AppId>"
                + "<InfoType>component_verify_ticket</InfoType>"
                + "<ComponentVerifyTicket>T</ComponentVerifyTicket></xml>")).isEqualTo("success");

        verify(tokenStore).saveTicket(COMPONENT_APPID, "T");
    }

    @Test
    @DisplayName("URL 校验：加密 echostr 解密后原样返回，明文 echostr 直接回显")
    void verifyUrlRoundTrip() {
        String encrypted = XmlCodec.extractEncrypt(CRYPT.encryptToXml("ECHO_9f3a1c", COMPONENT_APPID));
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = "nonce-url-check";

        assertThat(service.verifyUrl(CRYPT.computeMsgSignature(timestamp, nonce, encrypted),
                timestamp, nonce, encrypted)).isEqualTo("ECHO_9f3a1c");

        assertThat(service.verifyUrl(CRYPT.computeMsgSignature(timestamp, nonce, "PLAIN_ECHO"),
                timestamp, nonce, "PLAIN_ECHO")).isEqualTo("PLAIN_ECHO");
    }

    @Test
    @DisplayName("URL 校验签名不对时提示检查消息校验 Token")
    void verifyUrlRejectsBadSignature() {
        String encrypted = XmlCodec.extractEncrypt(CRYPT.encryptToXml("ECHO_x", COMPONENT_APPID));

        assertThatThrownBy(() -> service.verifyUrl("bad_signature", "1763000000", "nonce", encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("消息校验 Token");
    }
}
