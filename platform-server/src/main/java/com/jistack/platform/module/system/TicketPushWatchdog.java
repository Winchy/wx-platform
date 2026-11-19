package com.jistack.platform.module.system;

import com.jistack.platform.module.authorizer.AuthorizerService;
import com.jistack.platform.wechat.token.ComponentTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * component_verify_ticket 保活：微信约每 10 分钟推一次，超过 12 分钟没收到就请求重新推送。
 * 没有 ticket 就没有 component_access_token，整条代操作链路都会停摆。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketPushWatchdog {

    private static final long STALE_SECONDS = 12 * 60;

    private final PlatformConfigService configService;
    private final ComponentTokenService componentTokenService;
    private final AuthorizerService authorizerService;

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT5M")
    public void keepAlive() {
        PlatformConfig config = configService.load();
        if (!configService.isReady(config)) {
            return;
        }
        long age = componentTokenService.ticketAgeSeconds(config.getComponentAppid());
        if (age >= 0 && age < STALE_SECONDS) {
            return;
        }
        try {
            authorizerService.restartTicketPush();
            log.warn("component_verify_ticket 已 {}s 未更新，已请求微信重新推送", age);
        } catch (RuntimeException ex) {
            log.warn("请求微信重新推送 ticket 失败: {}", ex.toString());
        }
    }
}
