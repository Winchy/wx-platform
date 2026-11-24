-- 手机号验证码登录：账号需要能按手机号找回。可空——没绑定手机号的账号照旧只能用密码登录，
-- 已有账号（含默认 admin）迁移后都是 NULL，不会撞唯一索引（MySQL 唯一索引允许多个 NULL）。
ALTER TABLE sys_user
    ADD COLUMN phone VARCHAR(20) NULL COMMENT '手机号，验证码登录用；NULL 表示未绑定';

-- 一个手机号只能进一个账号，否则「验证码通过=登录成功」会同时命中多个账号，
-- 登录时无法确定该发谁的身份；唯一约束也是并发绑定的最后一道闸。
ALTER TABLE sys_user
    ADD UNIQUE KEY uk_sys_user_phone (phone);
