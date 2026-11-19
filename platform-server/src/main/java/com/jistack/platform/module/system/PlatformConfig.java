package com.jistack.platform.module.system;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
@ToString(exclude = {"componentSecret", "encodingAesKey"})
@TableName("wechat_platform_config")
public class PlatformConfig {

    public static final long SINGLETON_ID = 1L;

    @TableId(type = IdType.INPUT)
    private Long id;
    private String componentAppid;
    private String componentSecretEnc;
    private String messageToken;
    private String aesKeyEnc;
    /** 可清空的覆盖项：null 表示沿用环境变量，因此必须能把列写回 NULL。 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String siteBase;
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String apiBase;
    private Integer status;
    private LocalDateTime ticketReceivedAt;
    private LocalDateTime tokenExpiresAt;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 密钥只在内存里以明文形态短暂存在，绝不通过 @Data 的 toString 输出。 */
    @TableField(exist = false)
    private transient String componentSecret;
    @TableField(exist = false)
    private transient String encodingAesKey;
}
