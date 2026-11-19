package com.jistack.platform.module.template;

import com.jistack.platform.common.web.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 代码模板与草稿箱：草稿只能转为模板（微信没有删除草稿的接口）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    public record DraftToTemplateRequest(Integer templateType) {
    }

    @GetMapping("/drafts")
    public ApiResponse<List<Map<String, Object>>> localDrafts() {
        return ApiResponse.ok(templateService.localDrafts());
    }

    @PostMapping("/drafts/sync")
    public ApiResponse<List<Map<String, Object>>> syncDrafts() {
        return ApiResponse.ok(templateService.syncDrafts());
    }

    @PostMapping("/drafts/{draftId}/to-template")
    public ApiResponse<JsonNode> draftToTemplate(@PathVariable String draftId,
                                                 @RequestBody(required = false) DraftToTemplateRequest request) {
        Integer templateType = request == null ? null : request.templateType();
        return ApiResponse.ok(templateService.draftToTemplate(draftId, templateType));
    }

    @GetMapping("/templates")
    public ApiResponse<List<Map<String, Object>>> localTemplates() {
        return ApiResponse.ok(templateService.localTemplates());
    }

    @PostMapping("/templates/sync")
    public ApiResponse<List<Map<String, Object>>> syncTemplates() {
        return ApiResponse.ok(templateService.syncTemplates());
    }

    @DeleteMapping("/templates/{templateId}")
    public ApiResponse<Void> deleteTemplate(@PathVariable String templateId) {
        templateService.deleteTemplate(templateId);
        return ApiResponse.ok();
    }
}
