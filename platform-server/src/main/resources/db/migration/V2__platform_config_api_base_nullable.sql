-- 微信接口域名从「必填且写死默认值」改为可空：留空时回落到 WECHAT_API_BASE，平台配置里填的值只是覆盖项。
ALTER TABLE wechat_platform_config
    MODIFY COLUMN api_base VARCHAR(255) NULL COMMENT '微信接口基地址覆盖项，NULL 表示使用 WECHAT_API_BASE';

-- 建单行配置时代码会自动补上硬编码默认值，存量行里的这个值不代表运维意图，清空后环境变量才能生效。
UPDATE wechat_platform_config SET api_base = NULL WHERE api_base = 'https://api.weixin.qq.com';
