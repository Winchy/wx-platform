-- 微信开放平台第三方平台管理站点 —— 初始化 schema
-- 说明：本库为平台运营方自有库，租户业务（协会/会员/活动）不在这里，这里只管第三方平台与小程序研发发布链路。

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt',
    nickname      VARCHAR(64)  NOT NULL DEFAULT '',
    role          VARCHAR(32)  NOT NULL DEFAULT 'ADMIN' COMMENT 'ADMIN/OPERATOR/READONLY',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 禁用',
    last_login_at DATETIME     NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '平台后台账号';

CREATE TABLE IF NOT EXISTS wechat_platform_config (
    id                   BIGINT       NOT NULL COMMENT '固定 1，单行配置',
    component_appid      VARCHAR(64)  NULL COMMENT '第三方平台 AppID',
    component_secret_enc VARCHAR(512) NULL COMMENT '第三方平台 AppSecret（AES-256-GCM）',
    message_token        VARCHAR(64)  NULL COMMENT '消息校验 Token',
    aes_key_enc          VARCHAR(512) NULL COMMENT '消息加解 EncodingAESKey（AES-256-GCM）',
    site_base            VARCHAR(255) NULL COMMENT '本站对外基地址，覆盖 yml 配置',
    api_base             VARCHAR(255) NOT NULL DEFAULT 'https://api.weixin.qq.com',
    status               TINYINT      NOT NULL DEFAULT 0 COMMENT '0 未配置 1 已启用',
    ticket_received_at   DATETIME     NULL COMMENT '最近一次收到微信推送票据时间',
    token_expires_at     DATETIME     NULL COMMENT '当前 component_access_token 过期时间',
    updated_by         BIGINT       NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '第三方平台凭证与运行状态';

CREATE TABLE IF NOT EXISTS wechat_component_ticket (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    component_appid VARCHAR(64) NOT NULL,
    ticket          VARCHAR(255) NOT NULL,
    received_at     DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ticket_appid_time (component_appid, received_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '微信每 10 分钟推送的 component_ticket 历史';

CREATE TABLE IF NOT EXISTS wechat_auth_session (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    state             VARCHAR(64) NOT NULL COMMENT '授权链接的 state，防伪造',
    scene             VARCHAR(32) NOT NULL DEFAULT 'AUTHORIZE' COMMENT 'AUTHORIZE/REAUTH',
    pre_auth_code     VARCHAR(255) NULL,
    pre_auth_expired_at DATETIME  NULL,
    auth_code         VARCHAR(255) NULL,
    result_appid      VARCHAR(64)  NULL,
    status            VARCHAR(32)  NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING/CONFIRMED/EXPIRED/FAILED',
    error_message     VARCHAR(512) NULL,
    created_by        BIGINT       NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_state (state),
    KEY idx_auth_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '扫码授权会话';

CREATE TABLE IF NOT EXISTS wechat_authorizer (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    component_appid   VARCHAR(64) NOT NULL,
    authorizer_appid  VARCHAR(64) NOT NULL COMMENT '已授权小程序 AppID',
    nick_name         VARCHAR(128) NULL COMMENT '小程序名称',
    head_img          VARCHAR(512) NULL,
    alias             VARCHAR(128) NULL COMMENT '小程序微信号',
    principal_name    VARCHAR(128) NULL COMMENT '主体名称',
    original_username VARCHAR(128) NULL COMMENT 'gh_ 原始 ID',
    verify_type       INT          NULL COMMENT '-1 未认证 2 认证通过',
    service_type      INT          NULL COMMENT '0 订阅号 1 公众号 3 小程序',
    func_info_json    TEXT         NULL COMMENT '授权权限集 raw json',
    refresh_token_enc VARCHAR(512) NOT NULL COMMENT 'authorizer_refresh_token（AES-256-GCM）',
    token_expired_at  DATETIME     NULL,
    auth_state        VARCHAR(32)  NOT NULL DEFAULT 'AUTHORIZED' COMMENT 'AUTHORIZED/UNAUTHORIZED/EXPIRED',
    authorized_at     DATETIME     NULL,
    release_version   VARCHAR(64)  NULL COMMENT '线上版本号（best-effort 缓存）',
    verify_info       VARCHAR(512) NULL,
    remark            VARCHAR(512) NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_authorizer_appid (authorizer_appid),
    KEY idx_authorizer_state (auth_state)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '授权方（每个协会的小程序）';

CREATE TABLE IF NOT EXISTS wechat_authorizer_token (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    authorizer_appid  VARCHAR(64) NOT NULL,
    access_token      VARCHAR(512) NOT NULL,
    refresh_token_enc VARCHAR(512) NOT NULL,
    expires_at        DATETIME    NOT NULL,
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_appid (authorizer_appid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'authorizer_access_token 持久化副本（Redis 为热缓存）';

CREATE TABLE IF NOT EXISTS wechat_draft (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    draft_id     VARCHAR(64) NOT NULL COMMENT '草稿 ID',
    user_version VARCHAR(64)  NULL,
    user_desc    VARCHAR(255) NULL,
    screenshot   VARCHAR(512) NULL,
    latest       TINYINT     NOT NULL DEFAULT 0,
    created_time BIGINT       NULL COMMENT '微信返回的秒级时间戳',
    raw_json     TEXT         NULL,
    synced_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_draft_id (draft_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '小程序代码草稿箱';

CREATE TABLE IF NOT EXISTS wechat_template (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    template_id  VARCHAR(64) NOT NULL COMMENT '代码模板 ID',
    user_version VARCHAR(64)  NULL,
    user_desc    VARCHAR(255) NULL,
    draft_id     VARCHAR(64)  NULL,
    category_json TEXT        NULL,
    source       VARCHAR(64)  NULL,
    raw_json     TEXT         NULL,
    synced_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_template_id (template_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '小程序代码模板库';

CREATE TABLE IF NOT EXISTS wechat_app_release (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    authorizer_appid VARCHAR(64) NOT NULL,
    template_id      VARCHAR(64)  NULL,
    draft_id         VARCHAR(64)  NULL,
    ext_appid        VARCHAR(64)  NULL COMMENT '小程序 AppID（ext_json 里）',
    user_version     VARCHAR(64)  NULL,
    user_desc        VARCHAR(255) NULL,
    submit_duration  INT          NULL COMMENT '预计审核时长（秒）',
    qrcode_media_id  VARCHAR(128) NULL,
    status           VARCHAR(32)  NOT NULL DEFAULT 'INIT' COMMENT 'INIT/COMMITTED/AUDITING/AUDIT_PASS/AUDIT_REJECT/PUBLISHED/GRAY/REVERTED/FAILED',
    last_audit_id    VARCHAR(64)  NULL,
    audit_reason     TEXT         NULL,
    errcode          INT          NULL,
    errmsg           VARCHAR(512) NULL,
    created_by       BIGINT       NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_release_appid_time (authorizer_appid, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '每次代码提交/提审/发布的研发记录';

CREATE TABLE IF NOT EXISTS wechat_audit_record (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    authorizer_appid VARCHAR(64) NOT NULL,
    audit_id         VARCHAR(64) NOT NULL,
    status           INT          NULL COMMENT '0 通过 1 失败 2 待审核 3 撤回 4 审核前退款',
    reason           TEXT         NULL,
    detail_json      TEXT         NULL,
    submitted_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_queried_at  DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_id (audit_id),
    KEY idx_audit_appid (authorizer_appid, submitted_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '审核记录';

CREATE TABLE IF NOT EXISTS wechat_event_log (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    info_type       VARCHAR(64)  NULL COMMENT 'component_verify_ticket / authorized / unauthorized / updateauthorized / miniprogram_xxx',
    appid           VARCHAR(64)  NULL,
    body_type       VARCHAR(16)  NULL COMMENT 'XML/JSON',
    raw_body        TEXT         NULL,
    decrypted_body  TEXT         NULL,
    signature_ok    TINYINT      NOT NULL DEFAULT 0,
    handled         TINYINT      NOT NULL DEFAULT 0,
    error_message   VARCHAR(512) NULL,
    received_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_event_time (received_at),
    KEY idx_event_type (info_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '微信回调推送原始事件';

CREATE TABLE IF NOT EXISTS wechat_operation_log (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    operator_id      BIGINT       NULL,
    operator_name    VARCHAR(64)  NULL,
    action           VARCHAR(64) NOT NULL COMMENT '如 commit/submit_audit/release',
    authorizer_appid VARCHAR(64)  NULL,
    api_path         VARCHAR(255) NULL,
    request_summary  TEXT         NULL,
    response_summary TEXT         NULL,
    success          TINYINT     NOT NULL DEFAULT 1,
    errcode          INT          NULL,
    errmsg           VARCHAR(512) NULL,
    cost_ms          INT          NULL,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_oplog_time (created_at),
    KEY idx_oplog_appid (authorizer_appid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '后台操作与微信接口调用审计';
