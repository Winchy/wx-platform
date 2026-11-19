-- 小程序码图片链接：新版 getaccountbasicinfo 文档已不返回该字段，只有 api_get_authorizer_info 的 authorizer_info.qrcode_url 有，
-- 基本信息页要展示小程序码，只能在刷新授权方资料时落库。
ALTER TABLE wechat_authorizer
    ADD COLUMN qrcode_url VARCHAR(512) NULL COMMENT '小程序码图片链接（api_get_authorizer_info）' AFTER head_img;
