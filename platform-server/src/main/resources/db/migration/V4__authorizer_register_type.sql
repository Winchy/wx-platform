-- 改名接口只对「通过 API 注册」的小程序开放（微信 91001 not fast register），授权接入的存量小程序一律不行。
-- register_type 只有 api_get_authorizer_info 返回，基本信息页要在提交改名前判断，所以刷新资料时落库。
ALTER TABLE wechat_authorizer
    ADD COLUMN register_type INT NULL COMMENT '小程序注册方式（0 普通方式注册，非 0 通过 API 注册）' AFTER service_type;
