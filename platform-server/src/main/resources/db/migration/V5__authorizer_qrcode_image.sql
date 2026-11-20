-- 小程序码原图由后端代取（微信图片 CDN 有 Referer 防盗链，浏览器直连只会拿到「未经允许不可引用」占位图），
-- 取回来存一份，界面显示的是后端保存的这份。
-- 存库而不是存文件：deploy/docker-compose.yml 的 api 服务没挂任何 volume，写盘的图片每次重新构建就没了。
-- 单独一张表：不让 blob 进 wechat_authorizer，否则授权列表每次查询都拖着图片。
CREATE TABLE IF NOT EXISTS wechat_authorizer_qrcode (
    authorizer_appid VARCHAR(64)  NOT NULL COMMENT '小程序 AppID',
    source_url       VARCHAR(512) NULL COMMENT '取回这张图时用的微信链接，链接变了才重新取',
    content_type     VARCHAR(32)  NOT NULL COMMENT 'image/png、image/jpeg 等',
    image            MEDIUMBLOB   NOT NULL COMMENT '小程序码原图字节',
    synced_at        DATETIME     NOT NULL COMMENT '最近一次成功取图时间',
    PRIMARY KEY (authorizer_appid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '后端保存的小程序码图片';
