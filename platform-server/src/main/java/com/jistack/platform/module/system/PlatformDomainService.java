package com.jistack.platform.module.system;

import com.jistack.platform.common.web.BizException;
import com.jistack.platform.wechat.client.ComponentApi;
import com.jistack.platform.wechat.service.WechatCaller;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 第三方平台自身的小程序域名白名单（服务器域名 / 业务域名）。
 * 微信的规则是：授权给第三方代开发的小程序，其域名只能取自这里登记过的集合，
 * 所以授权小程序的域名配置必须以这里为前提。
 */
@Service
@RequiredArgsConstructor
public class PlatformDomainService {

    private static final String SERVER = "server";
    private static final String JUMP = "jump";

    private final WechatCaller caller;

    public Map<String, Object> read(String kind) {
        String normalized = requireKind(kind);
        JsonNode node = invoke(normalized, "get", null, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", normalized);
        result.put("published", split(node.path(field(normalized, "published_")).asText("")));
        result.put("testing", split(node.path(field(normalized, "testing_")).asText("")));
        result.put("invalid", split(node.path(field(normalized, "invalid_")).asText("")));
        result.put("limit", limit(normalized));
        return result;
    }

    /** 修改后微信不回清单，只回当次生效值，因此统一再读一次 get 作为界面结果。 */
    public Map<String, Object> update(String kind, String action, List<String> domains,
                                      Boolean modifyPublishedTogether) {
        String normalized = requireKind(kind);
        if (!List.of("add", "delete", "set").contains(action)) {
            throw new BizException("action 只能是 add/delete/set（读取请用 action=get 或 GET 接口）");
        }
        List<String> hosts = toHosts(domains, normalized);
        if ("set".equals(action) && hosts.size() > limit(normalized)) {
            throw new BizException("域名数量超过上限 " + limit(normalized) + " 个");
        }
        invoke(normalized, action, String.join(";", hosts), modifyPublishedTogether);
        return read(normalized);
    }

    private JsonNode invoke(String kind, String action, String domainValue, Boolean modifyPublishedTogether) {
        String apiAction = SERVER.equals(kind) ? "modify_wxa_server_domain" : "modify_wxa_jump_domain";
        return caller.component(apiAction, (api, token) -> SERVER.equals(kind)
                ? ComponentApi.modifyWxaServerDomain(api, token, action, domainValue, modifyPublishedTogether)
                : ComponentApi.modifyWxaJumpDomain(api, token, action, domainValue, modifyPublishedTogether));
    }

    /** 平台级业务域名校验文件：放到域名根目录后，微信才接受该域名。 */
    public Map<String, Object> confirmFile() {
        JsonNode node = caller.component("get_domain_confirmfile", ComponentApi::getPlatformDomainConfirmFile);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileName", node.path("file_name").asText(""));
        result.put("fileContent", node.path("file_content").asText(""));
        return result;
    }

    private static String requireKind(String kind) {
        if (SERVER.equals(kind) || JUMP.equals(kind)) return kind;
        throw new BizException("域名类型只能是 server（服务器域名）或 jump（业务域名），当前 " + kind);
    }

    private static String field(String kind, String prefix) {
        return prefix + (SERVER.equals(kind) ? "wxa_server_domain" : "wxa_jump_h5_domain");
    }

    private static int limit(String kind) {
        return SERVER.equals(kind) ? 1000 : 300;
    }

    /** 微信要求裸主机名：形如 www.qq.com，不带协议头、路径和端口。 */
    private static List<String> toHosts(List<String> input, String kind) {
        List<String> hosts = new ArrayList<>();
        if (input != null) {
            for (String item : input) {
                String host = toHost(item);
                if (!host.isEmpty() && !hosts.contains(host)) hosts.add(host);
            }
        }
        if (hosts.isEmpty()) {
            throw new BizException("域名列表不能为空，请填写形如 www.qq.com 的主机名（要移除域名请用 delete 指定具体条目）");
        }
        return hosts;
    }

    private static String toHost(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        value = value.replaceFirst("(?i)^https?://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        if (value.indexOf(':') >= 0) {
            throw new BizException("域名不能带端口：" + raw.trim());
        }
        return value.replaceAll("\\.+$", "");
    }

    private static List<String> split(String joined) {
        List<String> items = new ArrayList<>();
        if (joined == null || joined.isBlank()) return items;
        for (String part : joined.split(";")) {
            String value = part.trim();
            if (!value.isEmpty() && !items.contains(value)) items.add(value);
        }
        return items;
    }
}
