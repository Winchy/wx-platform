package com.jistack.platform.module.release;

import java.util.List;
import java.util.Map;

/**
 * 提交审核。微信要求每个页面一条 item；类目用名称 + 数字 id 成对表达（见 api_submitaudit 的 Body.item_list），
 * tag 只是空格分隔的页面标签，与类目无关。
 */
public record AuditSubmitRequest(List<Item> itemList,
                                 String feedbackInfo,
                                 String versionDesc,
                                 Integer grayPercentage,
                                 Map<String, Object> extra) {

    public record Item(String address,
                       String title,
                       List<String> tag,
                       String firstClass,
                       Integer firstId,
                       String secondClass,
                       Integer secondId,
                       String thirdClass,
                       Integer thirdId,
                       List<Map<String, Object>> keyWordList) {
    }
}
