package com.jistack.platform.module.release;

import com.jistack.platform.common.web.BizException;
import com.jistack.platform.wechat.client.WechatApiClient;
import com.jistack.platform.wechat.service.AuthorizerWriter;
import com.jistack.platform.wechat.service.WechatCaller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布链路的状态机：上传代码 → 提审 → 查审 → 发布/灰度/回退，每一步都要回写本地记录。
 * 这段逻辑原先只在连库的 E2E 里跑；这里让 WechatCaller 真的向 MockWebServer 发 HTTP，
 * 于是请求体字段名与本地状态流转在没有任何中间件的环境下也能一起验证。
 */
@DisplayName("发布链路：请求字段与本地状态流转")
class ReleaseWorkflowTest {

    private static final String APPID = "wxauthorizer000001";
    private static final String TOKEN = "AUTHORIZER_ACCESS_TOKEN";

    private final ObjectMapper json = new ObjectMapper();

    private MockWebServer server;
    private WechatCaller caller;
    private AppReleaseMapper releaseMapper;
    private AuditRecordMapper auditMapper;
    private ReleaseService service;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WechatApiClient api = new WechatApiClient(server.url("/").toString(), new RestTemplate(), json);

        caller = mock(WechatCaller.class);
        when(caller.authorizer(anyString(), anyString(), any())).thenAnswer(invocation -> {
            WechatCaller.AuthCall call = invocation.getArgument(2, WechatCaller.AuthCall.class);
            return call.apply(api, TOKEN);
        });
        when(caller.authorizerBinary(anyString(), anyString(), any())).thenAnswer(invocation -> {
            WechatCaller.BinaryCall call = invocation.getArgument(2, WechatCaller.BinaryCall.class);
            return call.apply(api, TOKEN);
        });

        releaseMapper = mock(AppReleaseMapper.class);
        auditMapper = mock(AuditRecordMapper.class);
        service = new ReleaseService(caller, mock(AuthorizerWriter.class), releaseMapper, auditMapper, json);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private void ok(String body) {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(body));
    }

    private RecordedRequest take() throws Exception {
        return server.takeRequest();
    }

    private JsonNode requestBody() throws Exception {
        return json.readTree(take().getBody().readUtf8());
    }

    private AppRelease existing(Long id, String status) {
        AppRelease release = new AppRelease();
        release.setId(id);
        release.setAuthorizerAppid(APPID);
        release.setStatus(status);
        return release;
    }

    // -------------------------------------------------- 上传代码

    @Test
    @DisplayName("commit 组装 ext_json：ext_appid 必须是被授权小程序自身 AppID")
    void commitBuildsExtJson() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");

        AppRelease saved = service.commit(APPID, new CommitRequest(" 9527 ", "1.2.0", "会员报名优化",
                null, Map.of("env", "prod"), List.of("pages/index/index")));

        JsonNode body = requestBody();
        assertThat(body.path("template_id").asText()).isEqualTo("9527");
        JsonNode extJson = json.readTree(body.path("ext_json").asText());
        assertThat(extJson.path("extAppid").asText()).isEqualTo(APPID);
        assertThat(extJson.path("ext").path("env").asText()).isEqualTo("prod");
        assertThat(extJson.path("extPages").get(0).asText()).isEqualTo("pages/index/index");
        // 微信不回 user_version，用请求值落库
        assertThat(saved.getStatus()).isEqualTo("COMMITTED");
        assertThat(saved.getTemplateId()).isEqualTo("9527");
        assertThat(saved.getUserVersion()).isEqualTo("1.2.0");
        assertThat(saved.getExtAppid()).isEqualTo(APPID);
        verify(caller).authorizer(eq(APPID), eq("commit"), any());
    }

    @Test
    @DisplayName("commit 传了 ext_json 就原样提交，不再二次组装")
    void commitKeepsGivenExtJson() throws Exception {
        ok("{\"errcode\":0}");

        service.commit(APPID, new CommitRequest("1", "1.0.0", "自定义 ext_json",
                "{\"extAppid\":\"wxcustom\",\"ext\":{\"a\":1}}", Map.of("b", 2), null));

        JsonNode extJson = json.readTree(requestBody().path("ext_json").asText());
        assertThat(extJson.path("extAppid").asText()).isEqualTo("wxcustom");
        assertThat(extJson.path("ext").path("a").asInt()).isEqualTo(1);
        assertThat(extJson.path("ext").has("b")).isFalse();
    }

    @Test
    @DisplayName("未选模板时不发请求，直接提示走模板库")
    void commitNeedsTemplate() {
        assertThatThrownBy(() -> service.commit(APPID, new CommitRequest(" ", "1", null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("模板");

        assertThat(server.getRequestCount()).isZero();
    }

    // -------------------------------------------------- 提交审核

    @Test
    @DisplayName("submit_audit 每个页面一条 item，落库状态转为 AUDITING")
    void submitAuditPersistsAuditId() throws Exception {
        ok("{\"errcode\":0,\"auditid\":\"A10086\",\"submit_duration\":180000}");
        AppRelease committed = existing(7L, "COMMITTED");
        when(releaseMapper.selectOne(any())).thenReturn(committed);

        String auditId = service.submitAudit(APPID, new AuditSubmitRequest(
                List.of(new AuditSubmitRequest.Item("pages/activity/detail?id=1", "活动详情",
                        List.of("活动", "报名"), "商业服务", 12, "公共服务", 132, null, null, null)),
                "协会活动报名，需登录后查看", "新增活动列表与报名", null, null));

        JsonNode body = requestBody();
        JsonNode item = body.path("item_list").get(0);
        assertThat(item.path("address").asText()).isEqualTo("pages/activity/detail?id=1");
        assertThat(item.path("tag").asText()).isEqualTo("活动 报名");
        assertThat(item.path("tag").isArray()).isFalse();
        assertThat(item.path("first_class").asText()).isEqualTo("商业服务");
        assertThat(item.path("first_id").asInt()).isEqualTo(12);
        assertThat(item.path("second_class").asText()).isEqualTo("公共服务");
        assertThat(item.path("second_id").asInt()).isEqualTo(132);
        assertThat(item.path("third_class").isMissingNode()).isTrue();
        assertThat(body.path("feedback_info").asText()).isEqualTo("协会活动报名，需登录后查看");
        assertThat(body.path("version_desc").asText()).isEqualTo("新增活动列表与报名");

        assertThat(auditId).isEqualTo("A10086");
        assertThat(committed.getStatus()).isEqualTo("AUDITING");
        assertThat(committed.getLastAuditId()).isEqualTo("A10086");
        assertThat(committed.getSubmitDuration()).isEqualTo(180000);
        verify(caller).authorizer(eq(APPID), eq("submit_audit"), any());

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditMapper).insert(record.capture());
        assertThat(record.getValue().getAuditId()).isEqualTo("A10086");
        assertThat(record.getValue().getStatus()).isEqualTo(2);
    }

    @Test
    @DisplayName("提审缺少页面或类目时拦住，避免微信 64101 类错误")
    void submitAuditValidatesItems() {
        assertThatThrownBy(() -> service.submitAudit(APPID,
                new AuditSubmitRequest(List.of(), null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少需要一个页面");

        // 只给类目名称、没有类目 id：微信会按未配置类目驳回
        assertThatThrownBy(() -> service.submitAudit(APPID, new AuditSubmitRequest(
                List.of(new AuditSubmitRequest.Item("pages/a/a", "标题", List.of(), "商业服务", null, null, null,
                        null, null, null)),
                null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("一级类目");

        assertThatThrownBy(() -> service.submitAudit(APPID, new AuditSubmitRequest(
                List.of(new AuditSubmitRequest.Item("pages/a/a", "标题", List.of(), "商业服务", 12, "公共服务", 132,
                        "高等", null, null)),
                null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("三级类目");

        List<AuditSubmitRequest.Item> six = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(new AuditSubmitRequest.Item("pages/a/a", null, null, "商业服务", 12, "公共服务", 132, null, null, null));
        }
        assertThatThrownBy(() -> service.submitAudit(APPID, new AuditSubmitRequest(six, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至多 5 个页面");

        assertThat(server.getRequestCount()).isZero();
    }

    // -------------------------------------------------- 审核结果

    @Test
    @DisplayName("审核驳回时把 reason 同步到提审记录与发布记录")
    void auditRejectedWritesReason() throws Exception {
        ok("{\"errcode\":0,\"status\":1,\"reason\":\"类目不符，请补充社交类目\"}");
        AppRelease auditing = existing(9L, "AUDITING");
        when(releaseMapper.selectOne(any())).thenReturn(auditing);

        AuditRecord record = service.refreshAuditStatus(APPID, "A10086");

        assertThat(requestBody().path("auditid").asText()).isEqualTo("A10086");
        assertThat(record.getStatus()).isEqualTo(1);
        assertThat(record.getReason()).contains("类目不符");
        assertThat(record.getDetailJson()).contains("\"status\":1");
        assertThat(auditing.getStatus()).isEqualTo("AUDIT_REJECT");
        assertThat(auditing.getAuditReason()).contains("类目不符");
        verify(caller).authorizer(eq(APPID), eq("get_auditstatus"), any());
    }

    @Test
    @DisplayName("审核通过（status=0）落 AUDIT_PASS")
    void auditPassed() throws Exception {
        ok("{\"errcode\":0,\"status\":0}");
        AppRelease auditing = existing(9L, "AUDITING");
        when(releaseMapper.selectOne(any())).thenReturn(auditing);

        assertThat(service.refreshAuditStatus(APPID, "A10086").getStatus()).isZero();
        assertThat(auditing.getStatus()).isEqualTo("AUDIT_PASS");
    }

    @Test
    @DisplayName("审核延后（status=4）仍是 AUDITING，不是驳回")
    void auditDelayedStaysAuditing() throws Exception {
        ok("{\"errcode\":0,\"status\":4,\"reason\":\"主体资质需进一步核验\"}");
        AppRelease auditing = existing(9L, "AUDITING");
        when(releaseMapper.selectOne(any())).thenReturn(auditing);

        AuditRecord record = service.refreshAuditStatus(APPID, "A10086");

        assertThat(record.getStatus()).isEqualTo(4);
        assertThat(record.getReason()).contains("资质");
        assertThat(auditing.getStatus()).isEqualTo("AUDITING");
    }

    @Test
    @DisplayName("驳回截图：拆 screenshot 的 media_id 列表，逐个用永久素材接口取回")
    void auditScreenshotsFetchedPerMediaId() throws Exception {
        AuditRecord rejected = new AuditRecord();
        rejected.setDetailJson("{\"errcode\":0,\"status\":1,\"reason\":\"页面打不开\",\"screenshot\":\"aaa|bbb\"}");
        when(auditMapper.selectOne(any())).thenReturn(rejected);
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 7, 8, 9};
        server.enqueue(new MockResponse().addHeader("Content-Type", "image/png")
                .setBody(new okio.Buffer().write(png)));
        server.enqueue(new MockResponse().addHeader("Content-Type", "image/png")
                .setBody(new okio.Buffer().write(png)));

        List<Map<String, Object>> shots = service.auditScreenshots(APPID);

        assertThat(shots).hasSize(2);
        RecordedRequest first = take();
        assertThat(first.getPath()).startsWith("/cgi-bin/material/get_material");
        assertThat(first.getMethod()).isEqualTo("POST");
        assertThat(json.readTree(first.getBody().readUtf8()).path("media_id").asText()).isEqualTo("aaa");
        assertThat(take().getPath()).startsWith("/cgi-bin/material/get_material");
        assertThat(shots.get(1).get("mediaId")).isEqualTo("bbb");
        assertThat(String.valueOf(shots.get(0).get("contentType"))).isEqualTo("image/png");
        assertThat(String.valueOf(shots.get(0).get("dataUrl"))).startsWith("data:image/png;base64,");
        verify(caller, times(2)).authorizerBinary(eq(APPID), eq("get_material"), any());
    }

    @Test
    @DisplayName("没有 screenshot 字段时不发素材请求，只回空清单")
    void auditScreenshotsAbsent() throws Exception {
        AuditRecord auditing = new AuditRecord();
        auditing.setDetailJson("{\"errcode\":0,\"status\":2}");
        when(auditMapper.selectOne(any())).thenReturn(auditing);

        assertThat(service.auditScreenshots(APPID)).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("撤回审核后回到 COMMITTED，可再次提审")
    void undoAudit() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        AppRelease auditing = existing(9L, "AUDITING");
        when(releaseMapper.selectOne(any())).thenReturn(auditing);

        service.undoAudit(APPID);

        assertThat(take().getPath()).startsWith("/wxa/undocodeaudit");
        assertThat(auditing.getStatus()).isEqualTo("COMMITTED");
    }

    // -------------------------------------------------- 发布 / 灰度 / 回退

    @Test
    @DisplayName("全量发布走 /wxa/release 并标记 PUBLISHED")
    void publish() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        AppRelease passed = existing(11L, "AUDIT_PASS");
        when(releaseMapper.selectOne(any())).thenReturn(passed);

        service.publish(APPID);

        assertThat(take().getPath()).startsWith("/wxa/release");
        assertThat(passed.getStatus()).isEqualTo("PUBLISHED");
        verify(caller).authorizer(eq(APPID), eq("release"), any());
    }

    @Test
    @DisplayName("分阶段发布把比例发给微信，本地记为 GRAY")
    void grayRelease() throws Exception {
        ok("{\"errcode\":0,\"base_info\":{\"release_version\":{}}}");
        AppRelease passed = existing(11L, "AUDIT_PASS");
        when(releaseMapper.selectOne(any())).thenReturn(passed);

        service.grayRelease(APPID, 20, true, null);

        JsonNode body = requestBody();
        assertThat(body.path("gray_percentage").asInt()).isEqualTo(20);
        assertThat(body.path("support_debuger_first").asBoolean()).isTrue();
        assertThat(body.has("support_experiencer_first")).isFalse();
        assertThat(passed.getStatus()).isEqualTo("GRAY");
    }

    @Test
    @DisplayName("灰度比例缺失或越界由本地拦住，0% 必须指定优先人群")
    void grayReleaseValidatesPercentage() {
        assertThatThrownBy(() -> service.grayRelease(APPID, 101, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("0~100");
        assertThatThrownBy(() -> service.grayRelease(APPID, -1, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("0~100");
        assertThatThrownBy(() -> service.grayRelease(APPID, null, true, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("灰度比例");
        // 100 是合法的全量灰度，0 则必须搭配开发者优先/体验成员优先
        assertThatThrownBy(() -> service.grayRelease(APPID, 0, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("优先");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("取消灰度后线上回到灰度前版本")
    void cancelGray() throws Exception {
        ok("{\"errcode\":0}");
        AppRelease gray = existing(11L, "GRAY");
        when(releaseMapper.selectOne(any())).thenReturn(gray);

        service.cancelGray(APPID);

        assertThat(take().getPath()).startsWith("/wxa/revertgrayrelease");
        assertThat(gray.getStatus()).isEqualTo("PUBLISHED");
        assertThat(gray.getAuditReason()).contains("灰度已取消");
    }

    @Test
    @DisplayName("灰度计划按微信字段名归一化，界面不必再猜拼写")
    void grayPlanUsesWeChatFieldNames() throws Exception {
        ok("{\"errcode\":0,\"gray_release_plan\":{\"status\":1,\"create_timestamp\":1517553721,"
                + "\"gray_percentage\":8,\"support_debuger_first\":true,\"support_experiencer_first\":false}}");

        Map<String, Object> plan = service.grayPlan(APPID);

        RecordedRequest request = take();
        assertThat(request.getPath()).startsWith("/wxa/getgrayreleaseplan");
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(plan).containsEntry("status", 1)
                .containsEntry("statusText", "执行中")
                .containsEntry("actionable", true)
                .containsEntry("grayPercentage", 8)
                .containsEntry("supportDebuggerFirst", true)
                .containsEntry("supportExperiencerFirst", false);
        assertThat(plan.get("createdAt")).isNotNull();
    }

    @Test
    @DisplayName("灰度已结束或微信没有计划时，不能再结束/取消灰度")
    void grayPlanWithoutActivePlan() throws Exception {
        ok("{\"errcode\":0,\"gray_release_plan\":{\"status\":3,\"gray_percentage\":100}}");
        assertThat(service.grayPlan(APPID))
                .containsEntry("statusText", "执行完毕")
                .containsEntry("actionable", false)
                .containsEntry("grayPercentage", 100);

        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        Map<String, Object> none = service.grayPlan(APPID);
        assertThat(none).containsEntry("actionable", false);
        assertThat(none.get("status")).isNull();
        assertThat(none.get("statusText")).isEqualTo("微信侧暂无灰度计划");
        assertThat(none.get("createdAt")).isNull();
    }

    @Test
    @DisplayName("灰度已在微信侧结束时，读计划顺手纠正本地卡在 GRAY 的发布单")
    void grayPlanCorrectsStaleLocalStatus() throws Exception {
        AppRelease stale = existing(11L, "GRAY");
        when(releaseMapper.selectOne(any())).thenReturn(stale);
        ok("{\"errcode\":0,\"gray_release_plan\":{\"status\":3,\"gray_percentage\":100}}");

        Map<String, Object> plan = service.grayPlan(APPID);

        assertThat(plan).containsEntry("localCorrected", true);
        assertThat(stale.getStatus()).isEqualTo("PUBLISHED");
        assertThat(stale.getAuditReason()).contains("微信侧灰度执行完毕");

        // 计划还在执行中时不能碰本地记录
        AppRelease running = existing(12L, "GRAY");
        when(releaseMapper.selectOne(any())).thenReturn(running);
        ok("{\"errcode\":0,\"gray_release_plan\":{\"status\":1,\"gray_percentage\":30}}");
        assertThat(service.grayPlan(APPID)).containsEntry("localCorrected", false);
        assertThat(running.getStatus()).isEqualTo("GRAY");
    }

    @Test
    @DisplayName("回退历史版本带 app_version")
    void revert() throws Exception {
        ok("{\"errcode\":0}");
        AppRelease published = existing(11L, "PUBLISHED");
        when(releaseMapper.selectOne(any())).thenReturn(published);

        service.revert(APPID, "1.1.0");

        RecordedRequest request = take();
        assertThat(request.getPath()).startsWith("/wxa/revertcoderelease");
        assertThat(request.getPath()).contains("app_version=1.1.0");
        assertThat(published.getStatus()).isEqualTo("REVERTED");
    }

    // -------------------------------------------------- 版本与素材

    @Test
    @DisplayName("版本信息聚合体验版、线上版与发布状态")
    @SuppressWarnings("unchecked")
    void versionInfoAggregates() throws Exception {
        ok("{\"errcode\":0,\"exp_info\":{\"exp_version\":\"1.2.0\",\"exp_desc\":\"活动报名\",\"exp_time\":1763000000},"
                + "\"release_info\":{\"release_version\":\"1.1.0\"}}");
        ok("{\"errcode\":0,\"status\":0}");

        Map<String, Object> info = service.versionInfo(APPID);

        Map<String, Object> exp = (Map<String, Object>) info.get("exp");
        assertThat(exp).containsEntry("version", "1.2.0").containsEntry("desc", "活动报名");
        // exp_time 是秒级时间戳，按毫秒换算会跑到 1970 年
        assertThat(exp.get("at")).isEqualTo(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(1763000000), java.time.ZoneId.systemDefault()));

        Map<String, Object> releaseInfo = (Map<String, Object>) info.get("release");
        assertThat(releaseInfo).containsEntry("version", "1.1.0")
                .containsEntry("desc", null)
                .containsEntry("at", null);
        assertThat(info.get("visitStatus")).isEqualTo(0);
        assertThat(take().getPath()).startsWith("/wxa/getversioninfo");
    }

    @Test
    @DisplayName("getvisitstatus 失败不影响版本信息展示")
    void versionInfoSurvivesVisitStatusFailure() throws Exception {
        ok("{\"errcode\":0,\"exp_info\":{\"exp_version\":\"1.2.0\"}}");
        ok("{\"errcode\":48001,\"errmsg\":\"api unauthorized\"}");

        Map<String, Object> info = service.versionInfo(APPID);

        assertThat(info.get("visitStatus")).isNull();
        // 没发布过线上版时微信压根不返回 release_info，界面要能把「没有」和「没读到」分开
        assertThat(info.get("release")).isNull();
    }

    @Test
    @DisplayName("暂停服务：先写 change_visitstatus，再重读状态作为返回值")
    void setVisitStatusReReadsAfterWrite() throws Exception {
        ok("{\"errcode\":0,\"errmsg\":\"ok\"}");
        ok("{\"errcode\":0,\"status\":0}");

        assertThat(service.setVisitStatus(APPID, "close")).isEqualTo(0);

        RecordedRequest change = take();
        assertThat(change.getPath()).startsWith("/wxa/change_visitstatus");
        assertThat(json.readTree(change.getBody().readUtf8()).path("action").asText()).isEqualTo("close");
        assertThat(take().getPath()).startsWith("/wxa/getvisitstatus");
        verify(caller).authorizer(eq(APPID), eq("change_visitstatus"), any());
    }

    @Test
    @DisplayName("action 只认 open/close，其他取值不发微信请求")
    void setVisitStatusRejectsUnknownAction() {
        assertThatThrownBy(() -> service.setVisitStatus(APPID, "pause"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("open");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("素材上传返回 mediaid")
    void uploadMedia() throws Exception {
        ok("{\"errcode\":0,\"mediaid\":\"MEDIA_1\",\"size\":2048}");

        assertThat(service.uploadMedia(APPID, "shot.png", "binary".getBytes())).isEqualTo("MEDIA_1");
        assertThat(take().getPath()).startsWith("/wxa/uploadmedia");
    }

    @Test
    @DisplayName("素材上传没有 mediaid 时按异常处理")
    void uploadMediaWithoutId() {
        ok("{\"errcode\":0}");

        assertThatThrownBy(() -> service.uploadMedia(APPID, "shot.png", "binary".getBytes()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("mediaid");
    }
}
