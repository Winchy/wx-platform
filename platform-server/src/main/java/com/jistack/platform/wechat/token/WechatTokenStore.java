package com.jistack.platform.wechat.token;

import com.jistack.platform.module.system.ComponentTicket;
import com.jistack.platform.module.system.ComponentTicketMapper;
import com.jistack.platform.module.system.PlatformConfig;
import com.jistack.platform.module.system.PlatformConfigMapper;
import com.jistack.platform.wechat.client.WechatApiClient;
import com.jistack.platform.wechat.client.WechatClientFactory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 票据与令牌的落库侧支撑：Redis 缓存之外提供重启可恢复的持久化副本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatTokenStore {

    private final ComponentTicketMapper ticketMapper;
    private final PlatformConfigMapper configMapper;
    private final WechatClientFactory clientFactory;

    public WechatApiClient client(PlatformConfig config) {
        return clientFactory.forConfig(config);
    }

    public void saveTicket(String componentAppid, String ticket) {
        ComponentTicket entity = new ComponentTicket();
        entity.setComponentAppid(componentAppid);
        entity.setTicket(ticket);
        entity.setReceivedAt(LocalDateTime.now());
        ticketMapper.insert(entity);
        // 只保留最近 200 条，避免票据历史无限增长
        var older = ticketMapper.selectList(new LambdaQueryWrapper<ComponentTicket>()
                .select(ComponentTicket::getId)
                .orderByDesc(ComponentTicket::getId)
                .last("limit 1 offset 200"));
        if (!older.isEmpty()) {
            ticketMapper.delete(new LambdaQueryWrapper<ComponentTicket>()
                    .le(ComponentTicket::getId, older.get(0).getId()));
        }
    }

    public String latestTicket(String componentAppid) {
        ComponentTicket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<ComponentTicket>()
                .eq(ComponentTicket::getComponentAppid, componentAppid)
                .orderByDesc(ComponentTicket::getId)
                .last("limit 1"));
        return ticket == null ? null : ticket.getTicket();
    }

    public LocalDateTime latestTicketTime(String componentAppid) {
        ComponentTicket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<ComponentTicket>()
                .eq(ComponentTicket::getComponentAppid, componentAppid)
                .orderByDesc(ComponentTicket::getId)
                .last("limit 1"));
        return ticket == null ? null : ticket.getReceivedAt();
    }

    public void recordComponentToken(String componentAppid, String token, LocalDateTime expiresAt) {
        PlatformConfig config = configMapper.selectById(PlatformConfig.SINGLETON_ID);
        if (config != null) {
            config.setTokenExpiresAt(expiresAt);
            configMapper.updateById(config);
        }
    }
}
