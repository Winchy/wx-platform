package com.jistack.platform.module.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 备案状态解码：微信用 1024~1031 八个值区分「未备案」缺哪个前置条件，
 * 这里按官方枚举逐值核对位解析，避免界面上只写一句「未备案」。
 */
@DisplayName("备案状态：1024~1031 的前置条件按位解码")
class ReleaseIcpStatusTest {

    @Test
    @DisplayName("已备案与各审核阶段有中文名，且不含缺失项")
    void filedAndInAudit() {
        assertThat(ReleaseService.icpStatusText(6)).isEqualTo("已备案");
        assertThat(ReleaseService.icpStatusText(2)).isEqualTo("备案平台审核中");
        assertThat(ReleaseService.icpStatusText(3)).isEqualTo("备案平台审核驳回");
        assertThat(ReleaseService.icpStatusText(4)).isEqualTo("管局审核中");
        assertThat(ReleaseService.icpStatusText(5)).isEqualTo("管局审核驳回");
        assertThat(ReleaseService.icpMissingPrerequisites(6)).isEmpty();
        assertThat(ReleaseService.icpMissingPrerequisites(4)).isEmpty();
    }

    @Test
    @DisplayName("1024 是三项前置条件都满足、只差提交备案")
    void notFiledWithoutMissing() {
        assertThat(ReleaseService.icpStatusText(1024)).isEqualTo("未备案");
        assertThat(ReleaseService.icpMissingPrerequisites(1024)).isEmpty();
    }

    @Test
    @DisplayName("低位 1/2/4 分别对应基本信息、类目、认证")
    void singleBitFlags() {
        assertThat(ReleaseService.icpMissingPrerequisites(1025))
                .containsExactly("小程序基本信息未填写（名称/头像/简介）");
        assertThat(ReleaseService.icpMissingPrerequisites(1026))
                .containsExactly("没有审核通过的小程序类目");
        assertThat(ReleaseService.icpMissingPrerequisites(1028))
                .containsExactly("主体未完成微信认证");
    }

    @Test
    @DisplayName("组合值一次性列全缺失项，1031 三项皆缺")
    void combinedFlags() {
        assertThat(ReleaseService.icpMissingPrerequisites(1027)).containsExactly(
                "小程序基本信息未填写（名称/头像/简介）", "没有审核通过的小程序类目");
        assertThat(ReleaseService.icpMissingPrerequisites(1030)).containsExactly(
                "没有审核通过的小程序类目", "主体未完成微信认证");
        List<String> all = ReleaseService.icpMissingPrerequisites(1031);
        assertThat(all).hasSize(3);
        assertThat(String.join("|", all)).contains("基本信息", "类目", "认证");
    }

    @Test
    @DisplayName("读不到状态时不谎报缺失项")
    void unknownStatus() {
        assertThat(ReleaseService.icpStatusText(-1)).isEqualTo("备案状态未知");
        assertThat(ReleaseService.icpStatusText(1032)).isEqualTo("备案状态未知");
        assertThat(ReleaseService.icpMissingPrerequisites(-1)).isEmpty();
        assertThat(ReleaseService.icpMissingPrerequisites(1032)).isEmpty();
    }
}
