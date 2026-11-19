package com.jistack.platform.module.release;

import java.util.List;
import java.util.Map;

/** 上传代码：模板 ID + ext_json（必须包含 extAppid）。 */
public record CommitRequest(String templateId,
                            String userVersion,
                            String userDesc,
                            String extJson,
                            Map<String, Object> ext,
                            List<String> extPages) {
}
