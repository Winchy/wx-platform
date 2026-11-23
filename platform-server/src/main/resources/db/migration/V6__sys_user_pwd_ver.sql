-- 改密后让旧令牌立刻失效：令牌里带这个版本号，每个请求都要与库里的当前值一致才放行。
-- 不加这一列，改密只能挡住「新的一次登录」，已经发出去的无状态令牌还能带着凭据跑满 12 小时 TTL。
ALTER TABLE sys_user
    ADD COLUMN pwd_ver INT NOT NULL DEFAULT 0 COMMENT '密码版本号，改密即 +1，旧令牌当场作废';
