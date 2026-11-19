package com.jistack.platform.module.template;

import com.jistack.platform.common.web.BizException;
import com.jistack.platform.wechat.client.MiniProgramApi;
import com.jistack.platform.wechat.service.WechatCaller;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码草稿箱与模板库。第三方平台只能把「草稿」转成「模板」，草稿本身由小程序开发者工具上传产生，
 * 没有删除草稿的接口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final WechatCaller caller;
    private final DraftMapper draftMapper;
    private final CodeTemplateMapper templateMapper;

    @Transactional
    public List<Map<String, Object>> syncDrafts() {
        JsonNode response = caller.component("gettemplatedraftlist",
                (api, token) -> MiniProgramApi.templateDraftList(api, token));
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : response.path("draft_list")) {
            String draftId = text(item, "draft_id");
            if (!StringUtils.hasText(draftId)) {
                continue;
            }
            Draft existing = draftMapper.selectOne(new LambdaQueryWrapper<Draft>()
                    .eq(Draft::getDraftId, draftId));
            Draft entity = existing == null ? new Draft() : existing;
            entity.setDraftId(draftId);
            entity.setUserVersion(text(item, "user_version"));
            entity.setUserDesc(text(item, "user_desc"));
            entity.setScreenshot(text(item, "screenshot"));
            entity.setCreatedTime(item.path("create_time").asLong(0));
            entity.setRawJson(item.toString());
            entity.setSyncedAt(LocalDateTime.now());
            if (existing == null) {
                draftMapper.insert(entity);
            } else {
                draftMapper.updateById(entity);
            }
            result.add(toDraftMap(entity));
        }
        return result;
    }

    /** 只读本地缓存，避免每次进入页面都打微信接口。 */
    public List<Map<String, Object>> localDrafts() {
        return draftMapper.selectList(new LambdaQueryWrapper<Draft>().orderByDesc(Draft::getCreatedTime))
                .stream().map(TemplateService::toDraftMap).toList();
    }

    /** 草稿转模板，template_type: 0 普通模板（默认）1 标准模板库。 */
    @Transactional
    public JsonNode draftToTemplate(String draftId, Integer templateType) {
        long id;
        try {
            id = Long.parseLong(draftId == null ? "" : draftId.trim());
        } catch (NumberFormatException ex) {
            throw new BizException("草稿 ID 必须是数字：" + draftId);
        }
        JsonNode response = caller.component("addtotemplate",
                (api, token) -> MiniProgramApi.addToTemplate(api, token, id, templateType));
        syncTemplates();
        return response;
    }

    @Transactional
    public List<Map<String, Object>> syncTemplates() {
        JsonNode response = caller.component("gettemplatelist",
                (api, token) -> MiniProgramApi.templateList(api, token));
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : response.path("template_list")) {
            String templateId = text(item, "template_id");
            if (!StringUtils.hasText(templateId)) {
                continue;
            }
            CodeTemplate existing = templateMapper.selectOne(new LambdaQueryWrapper<CodeTemplate>()
                    .eq(CodeTemplate::getTemplateId, templateId));
            CodeTemplate entity = existing == null ? new CodeTemplate() : existing;
            entity.setTemplateId(templateId);
            entity.setUserVersion(text(item, "user_version"));
            entity.setUserDesc(text(item, "user_desc"));
            entity.setDraftId(text(item, "draft_id"));
            entity.setSource(text(item, "source_miniprogram"));
            entity.setCategoryJson(item.path("category_list").toString());
            entity.setRawJson(item.toString());
            entity.setSyncedAt(LocalDateTime.now());
            if (existing == null) {
                templateMapper.insert(entity);
            } else {
                templateMapper.updateById(entity);
            }
            result.add(toTemplateMap(entity));
        }
        return result;
    }

    public List<Map<String, Object>> localTemplates() {
        return templateMapper.selectList(new LambdaQueryWrapper<CodeTemplate>().orderByDesc(CodeTemplate::getId))
                .stream().map(TemplateService::toTemplateMap).toList();
    }

    @Transactional
    public void deleteTemplate(String templateId) {
        if (!StringUtils.hasText(templateId)) {
            throw new BizException("缺少 template_id");
        }
        caller.component("deletetemplate", (api, token) -> MiniProgramApi.deleteTemplate(api, token, templateId));
        templateMapper.delete(new LambdaQueryWrapper<CodeTemplate>().eq(CodeTemplate::getTemplateId, templateId));
    }

    private static Map<String, Object> toDraftMap(Draft entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("draftId", entity.getDraftId());
        map.put("userVersion", entity.getUserVersion());
        map.put("userDesc", entity.getUserDesc());
        map.put("screenshot", entity.getScreenshot());
        map.put("createdTime", entity.getCreatedTime());
        map.put("createdAt", toLocal(entity.getCreatedTime()));
        map.put("syncedAt", entity.getSyncedAt());
        return map;
    }

    private static Map<String, Object> toTemplateMap(CodeTemplate entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("templateId", entity.getTemplateId());
        map.put("userVersion", entity.getUserVersion());
        map.put("userDesc", entity.getUserDesc());
        map.put("draftId", entity.getDraftId());
        map.put("source", entity.getSource());
        map.put("syncedAt", entity.getSyncedAt());
        return map;
    }

    private static LocalDateTime toLocal(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
