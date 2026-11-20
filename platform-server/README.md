# platform-server — 微信第三方平台管理站后端

管理「小程序开发与发布」的后端：承接开放平台第三方平台的授权事件、维护令牌、代授权方小程序调用微信接口。
前端在 `../platform-web`（Vue3 + Element Plus）。

## 1. 本地部署与测试

### 1) 最小依赖（日常开发用这个）

`application.yml` 的默认值就是「本机 3306，root/jistack」+「本机 6379，无密码」，
所以起两个容器即可，不需要 `.env`：

```bash
docker run -d --name jistack-mysql -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=jistack -e MYSQL_DATABASE=jistack_platform -e TZ=Asia/Shanghai \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
docker run -d --name jistack-redis -p 6379:6379 redis:7-alpine

# 主密钥：加密 component_secret / EncodingAESKey / authorizer_refresh_token
export PLATFORM_MASTER_KEY=$(openssl rand -base64 32)
export PLATFORM_JWT_SECRET=$(openssl rand -hex 32)

cd platform-server && mvn -B spring-boot:run   # Flyway 自动建表，首次写入单行平台配置 id=1 与 admin/admin123
# 打包：mvn -B package → target/platform-server-0.1.0-SNAPSHOT.jar
```

默认账号 `admin / admin123`，登录后请立刻在系统内改密（`sys_user`，BCrypt 存储）。

```bash
cd platform-web && npm ci        # Node 需 ≥20，仓库带 .nvmrc=24：nvm use
npm run dev                      # http://127.0.0.1:5173，/api 与 /wx 代理到本机 8080
npm run build                    # vue-tsc --noEmit + vite build，产物 dist/
```

> Node 16 下 `npm run build` 会报 `crypto.getRandomValues is not a function`，那是 Vite 5 要求 Node ≥18，切版本即可。

### 2) 跑测试

```bash
cd platform-server && mvn -B test
```

- 185 个用例不需要任何中间件：安全模式加解密闭环、MockWebServer 锁定的微信接口契约（路径/字段名/请求体/分帧方式）、
  发布链路状态机（commit→提审→审核→发布/灰度/回退）、令牌链刷新与轮换回写、回调分发（含不带参数探测）、
  图片直链抓取失败分型、微信接口域名的优先级、Web 层参数绑定与错误码映射。
- 17 个 `PlatformEndToEndTest` 只在 3306 与 6379 都能连上时才执行（`@EnabledIf("infrastructureReady")`）。
  它们用随机库名 `wxplat_e2e_*` + Redis db9，跑完 drop，不污染开发数据；微信侧由 MockWebServer 桩出来，
  不需要真实凭据、也不需要公网域名。没起容器时这些用例显示 skipped，不是失败。

### 3) 要连真微信：内网穿透

本地拿不到 `component_verify_ticket` 就没有 component_access_token，接口会报 409 并提示检查「授权事件接收 URL」。
微信只接受 80/443 上的 https 域名，所以：

1. 用 cpolar / frp / cloudflared 之类把本机 8080 暴露成一个 https 域名。
2. 开放平台后台「开发配置」填：授权事件接收 URL `https://<隧道域名>/wx/callback`，
   消息与事件接收 URL `https://<隧道域名>/wx/callback/$APPID$`。
3. 启动前 `export PLATFORM_SITE_BASE=https://<隧道域名>`。默认值 `http://localhost:8080` 只能点站内页面，
   用它拼出来的授权二维码微信打不开。
4. 后台的 Token / EncodingAESKey 必须和站内「平台配置」填的完全一致，否则签名校验一直失败。
5. 「平台配置 → IP 白名单」填本机出口公网 IP，缺失时所有调用报 40164。
6. ticket 约每 10 分钟推一次，急用时在站内「平台健康」点「重新开启推送」（api_start_push_ticket）。

### 4) 整栈容器（复刻生产形态）

`deploy/docker-compose.yml` 是生产编排：MySQL + Redis + api + web。公网入口和 TLS 交给**宿主机上已有的 nginx**，
compose 里只有 web 映射 `127.0.0.1:8088:80`，api/mysql/redis 都不出容器，所以它必须先有 `deploy/.env`：

```bash
cd deploy && cp .env.example .env    # .env 含密钥，不要提交到仓库
# 填 PLATFORM_DOMAIN（需已备案并解析到本机）、MySQL 两个密码、REDIS_PASSWORD、
# PLATFORM_MASTER_KEY、PLATFORM_JWT_SECRET
docker compose up -d --build
```

宿主机 nginx 的站点配置（`server_name` 用 `.env` 里的 `PLATFORM_DOMAIN`，证书自己签发与续期）：

```nginx
server {
    listen 443 ssl http2;
    server_name platform.example.com;
    ssl_certificate     /path/fullchain.pem;
    ssl_certificate_key /path/privkey.pem;

    # 代码包/素材上传走后端 multipart：容器内 nginx 已放 60m，这层留默认 1m 会直接 413
    client_max_body_size 60m;

    location / {
        # 只做透传，/api/ 与 /wx/ 的分发在 web 容器内的 nginx 里（platform-web/nginx.conf）
        proxy_pass http://127.0.0.1:8088;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        # application.yml 的 forward-headers-strategy 靠它判断协议，不能漏
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
server { listen 80; server_name platform.example.com; return 301 https://$host$request_uri; }
```

换成宿主机 nginx 后有四点只在微信这边成立：

1. **前端与 `/api` 必须同源**。`WebConfig` 的 CORS 只放行 `localhost`/`127.0.0.1`，上面这一层代理天然同源；
   把后端单独挂到另一个域名或端口，浏览器会全部拦掉。
2. **`/wx/callback` 原样透传**，`msg_signature`/`timestamp`/`nonce`/`echostr` 不能被改写，这层别加 rewrite。
   装了 WAF/宝塔「GET/POST 防御」的机器要把 `/wx/` 加白名单——密文 POST body 很容易被误拦成 403，且这类问题本地复现不了。
3. **证书签发续期变成你的事**。Caddy 在的时候是自动的；现在要么面板一键 Let's Encrypt，要么 certbot 定时器。
   微信仍要求 80/443 + 已备案 https，备案接入商得是这台机器所在的云。
4. **nginx 自己跑在容器里时**（宝塔 docker 版之类），`127.0.0.1:8088` 是不通的，
   要让 nginx 与这套 compose 进同一个 docker 网络（`proxy_pass http://web:80`），或把端口绑到宿主网络。

本机没有已备案域名时签不出可信证书，这条链路只能在服务器上验。
想只借它的依赖，用 `docker compose up -d mysql redis`，再 `export MYSQL_USERNAME/MYSQL_PASSWORD/REDIS_PASSWORD`
覆盖 `application.yml` 的默认值（那台 MySQL 的 root 密码来自 `.env`，不是 jistack）。

> 这台机器上没有可用的 nginx 时，`deploy/Caddyfile` 仍是兜底：加回一个 `caddy` 服务（`image: caddy:2`，映射 80/443，
> 挂 `./Caddyfile:/etc/caddy/Caddyfile:ro`，传 `PLATFORM_DOMAIN` 环境变量给文件里的 `{$PLATFORM_DOMAIN}`）即可自动签证书，
> 文件内容只有 `{$PLATFORM_DOMAIN} { reverse_proxy web:80 }` 一行；此时把 web 的 `127.0.0.1:8088:80` 去掉，别留两个入口。

## 2. 微信开放平台后台要填的东西

本站对外只提供两个回调地址，路径固定：

| 开放平台位置 | 填写值 |
| --- | --- |
| 第三方平台详情 → 开发配置 → **授权事件接收 URL** | `https://<本站域名>/wx/callback` |
| 第三方平台详情 → 开发配置 → **消息与事件接收 URL** | `https://<本站域名>/wx/callback/$APPID$` |
| 小程序「业务域名/服务器域名」校验 | 站内 `配置 → 域名` 页直接读写，无需在此填写 |

> 两个地址都指向同一个入口：`GET /wx/callback` 处理 URL 校验（安全模式下解密 echostr 后原样返回），
> `POST /wx/callback` 处理推送（`component_verify_ticket`、`authorized`、`updateauthorized`、`unauthorized`）。
> 微信只接受 80/443，且**必须 https**；本地联调用内网穿透工具把 8080 暴露成 https 域名。

同一页面的其余配置：

1. **AppID / AppSecret**：第三方平台自己的凭据（不是小程序的），填到站内 `平台配置`。
2. **消息加解密密钥**：`Token`（自定义 3–32 位）与 `EncodingAESKey`（43 位）也要同时填进站内 `平台配置`，
   两边的 Token/AESKey 必须完全一致，否则签名校验一直失败。
3. **IP 白名单**：填调用方服务器公网 IP。缺失时所有接口报 `40164`/`61004`。
4. **权限集（授权必选）**：建议至少勾选
   - **小程序代码管理 / 审核（`funcscope 18`）**：微信文档明确 `commit`、`submit_audit`、`undo_audit`、
     `get_auditstatus`、`speedupaudit`、`release`、`grayrelease` 都归这个权限集，发布链路只勾 `1` 会报 `48001`
   - 小程序的开发与管理（`1`）
   - 小程序的基本信息设置（`4`）
   - 小程序的服务器域名/业务域名配置（`3`）
   - 小程序的类目管理（`17`）
   站内「发布工作台」顶部会显示该小程序实际授予的权限集 id，缺 `18` 时直接弹提示；缺权限的调用报 `48001`。
   「扫码授权」页也可以直接指定权限集（链接带 `category_id_list=18|1`，中竖线分隔），但指定后管理员**只能**授权所选这几项，
   所以界面默认留空＝已发布的全部权限集，只在补齐权限时按需指定。
5. **指令化管理 / 客服** 等非本期功能可不勾。

## 3. 接入流程（站内操作顺序）

```
平台配置填凭据 → 微信开始每 10 分钟推 ticket（站内「平台健康」可看 ticket 时间与重试推送）
→ 授权接入：生成扫码链接（PC componentloginpage / 手机 bindcomponent）
→ 管理员扫码确认 → 回调带回 auth_code → 换 authorizer_refresh_token（落库加密，且微信每次刷新都会轮换）
→ 选代码模板上传代码（commit：template_id 是数字，user_version / user_desc 微信必填，ext_json.extAppid = 被授权小程序 AppID）
→ 体验版二维码（path 需 urlencode，带查询串的页面路径由 WechatApiClient 统一转义）
→ 提交审核（每个页面一条 item_list：address + 一级/二级类目的「名称 + id」都必填，tag 是空格分隔的页面标签而非类目编号，单次至多 5 个页面）
→ 审核中可「加速审核」（消耗加急额度，微信侧预计 2-12 小时审完）或「撤回审核」
→ 审核通过后发布 / 灰度（gray_percentage 是 0~100 的整数，100 即全量；0 必须搭配「开发者优先」或「体验成员优先」）/ 回退
```

## 4. 接口分组

| 前缀 | 说明 |
| --- | --- |
| `/api/auth/*` | 登录、当前用户（`login` 免鉴权） |
| `/api/platform/*` | 平台凭据、健康检查、ticket 列表、重新开启推送、`domains/{kind}`（`server`/`jump`，第三方平台账号自身的域名白名单） |
| `/api/authorizers/*` | 授权小程序列表/详情/刷新/同步/解除、授权会话 |
| `/api/authorizers/{appid}/…` | `code` `experience-qrcode` `pages` `code-info` `audit*` `publish` `gray-release*` `revert*` `release/*` |
| `/api/authorizers/{appid}/…` | `basic-info` `nickname*` `signature` `avatar` `domains` `webview-domains` `categories*` `testers*` `media` |
| `/api/templates` `/api/drafts` | 代码模板与草稿箱（草稿只能「转为模板」，微信无删除草稿接口） |
| `/api/events` `/api/logs` | 微信推送记录、每次代调用的审计日志 |
| `/wx/callback` `/wx/callback/{appid}` `/wx/auth/callback` | 微信侧回调，非前端接口。裸路径收票据与授权事件；`{appid}` 是「消息与事件接收 URL」里 `$APPID$` 被微信替换后的形式，收小程序侧消息与事件；`/wx/auth/callback` 授权完 302 回 `/authorizers` |

响应统一 `{code, message, data}`，`code=0` 为成功；微信的 `errcode` 原样透传在 `code` 里，便于前端按错误码提示。
鉴权：`Authorization: Bearer <token>`；`<img>` 类 GET 接口支持 `?token=` 便于直接展示二维码。

## 5. 已知约束

- `PLATFORM_MASTER_KEY` 变更后，库中已加密的 `component_secret` / `EncodingAESKey` / `authorizer_refresh_token` 无法解密，
  必须重新填写凭据并重新授权。
- 未设置主密钥时 `SecretCipher` 使用临时密钥（启动日志会告警），重启即失效，仅供本地试跑。
- 体验版二维码、审核状态等接口有微信侧频控/日配额，超限报 `45009`/`45011`。
- `get_audit_status` 只能查最近若干条，历史审核详情以 `wechat_audit_record` 落库记录为准。
- 「加速审核」每次消耗第三方平台的加急额度，站内不校验剩余额度，超限由微信返回错误码原样提示；
  `/audit/speedup` 不传 `auditId` 时回落到最近一次提审记录，无提审记录返回 404。
- 微信接口域名以「平台配置」页的 `api_base` 优先，留空才用 `WECHAT_API_BASE`；`V2` 迁移会把旧版本自动补上的
  `https://api.weixin.qq.com` 清成 NULL，所以升级后这一列变空是预期行为，不是凭据丢了。
- 授权小程序的服务器域名与业务域名，只能取自第三方平台账号自己登记的白名单
  （「平台配置」页最下方的面板，对应 `modify_wxa_server_domain` / `modify_wxa_jump_domain`）。
  没在这里登记过的域名，`modify_domain` / `setwebviewdomain` 会被微信拒绝；业务域名还要求把校验文件放到域名根目录。
  小程序域名页在提交前就比对白名单并把不在里面的域名点名拦下，不再让微信去拒绝后由运维猜原因。
- 服务器域名每月只能提交 50 次修改申请，且 `action=set` 是整份覆盖、`add` 只追加，两者混用会静默丢掉另一半改动，
  因此界面按差异只发对应清单，且无差异时不发请求。
- 平台白名单默认只改**测试版**（与微信 `is_modify_published_together` 的缺省一致）；勾上「同时修改全网发布版」后
  删除或覆盖会立刻影响该平台下**所有已发布**的小程序，界面把这句后果显示在勾选框下方。
- 审核驳回时微信除了 `reason` 还会给 `screenshot`：**竖线分隔的 media_id 列表**（仅 `status=1` 出现），内容要用
  `POST /cgi-bin/material/get_material`（`authorizer_access_token`，body 只有 `media_id`）逐个取回。图片素材**直接回二进制**、
  出错时才回 JSON，所以按 Content-Type 区分错误、再按文件头判断 MIME（素材接口不回 Content-Type），界面拿到的是 `data:` URL。
  后端 `GET /api/authorizers/{appid}/audit/screenshots` 返回 `[{mediaId, contentType, dataUrl}]`，
  「审核结果」面板只在驳回时按需请求一次，缩略图用 `el-image` 点开可放大。
- multipart 上传（`/cgi-bin/media/upload` 头像与改名材料、`/wxa/uploadmedia` 提审素材）**必须带 Content-Length**。
  Spring 把 `MultiValueMap` 交给 `FormHttpMessageConverter` 时实测发的是 `Transfer-Encoding: chunked`
  （`SimpleClientHttpRequestFactory` 与 `JdkClientHttpRequestFactory` 都一样），微信在接收请求体途中直接断连接，
  报 `Error writing request body to server`；文档示例是 `curl -F`，即一次性带长度的请求体。
  因此 `WechatApiClient.multipart` 自己拼 boundary 与分段、显式设长度，不再走转换器；
  MockWebServer 两种分帧都收，所以只有 `multipartUploadSendsContentLength` 那条用例能从请求头上守住。
- 接口改名（`/wxa/setnickname`）**只对「通过 API 注册」的小程序开放**，否则微信回 `91001 not fast register`；
  授权接入的存量小程序基本都是 `register_type=0`（普通方式注册），只能由管理员在微信公众平台「设置 → 基本设置 → 名称」修改。
  `register_type` 只有 `api_get_authorizer_info` 返回，刷新授权资料时落库，`basic-info` 回 `registerType` 与
  `apiRenameSupported`（未刷新过时为 `null`，界面不拦），改名提交入口也会先拦下确证的 `0`。
- 小程序码链接（`api_get_authorizer_info` 的 `authorizer_info.qrcode_url`）**不能直接放进 `<img src>`**：微信按 Referer 防盗链，
  网页里引用只会拿到「此图片来自微信公众平台 未经允许不可引用」的占位图，而且 HTTP 仍是 200，界面看不出失败。
  现网这条链接指向的是微信自有图片 CDN（`mmbiz.qpic.cn`，头像在 `qlogo.cn`），**不是** `mp.weixin.qq.com/cgi-bin/showqrcode`，
  所以服务端抓取白名单放行 `*.weixin.qq.com`、`*.qpic.cn`、`*.qlogo.cn`，以及与 `api_base` 同主机的地址（后者供测试桩与私有部署）；
  微信 CDN 上仍有 `http://` 写法，同一 CDN 路径支持 https，因此这类主机的 http 直接改写成 https 而不是拒绝。
  白名单只比主机名结尾，`mmbiz.qpic.cn.attacker.com` 这种伪装会被拦下（由 `WechatApiContractTest` 守住）。
- 取回的原图**存进 `wechat_authorizer_qrcode`（主键 appid，`MEDIUMBLOB`）**，`GET /api/authorizers/{appid}/qrcode`
  回 `{url, contentType, size, dataUrl, source, savedAt, staleReason}`：`source=local` 表示这次读的是后端已保存的副本
  （**不发微信请求**），`source=wechat` 表示刚从微信取回并覆盖了副本；`?refresh=true` 是界面「重新取图」用的强制回源。
  回源失败但库里已有副本时仍返回副本，把失败原因放进 `staleReason` 让界面写明，而不是整块空白；库里没副本才把错误抛出。
  副本只有在落库的 `qrcode_url` 变了（微信换了 ticket）或超过 7 天才回源，比较链接前先归一 http/https。
  存库而不是存容器磁盘：`deploy/docker-compose.yml` 的 `api` 服务没挂卷，文件在每次 `build`/`up -d` 后就没，
  而单独一张表（不是 `wechat_authorizer` 的一列）能让列表查询永远不把图片字节捞出来。
- `showqrcode` 的 `ticket` 是微信一次性签发的，**过期后直接回 HTTP 404 且响应体为空、不带 errcode**（本机对真实
  `mp.weixin.qq.com` 域名实测过这个形状）。落库链接里的 ticket 多半随时间失效，所以取图失败会自动调一次
  `api_get_authorizer_info` 换新链接重试，响应里的 `url` 是实际取到图的那条（否则「复制图片链接」复制出去仍是过期 ticket）；
  新链接与旧链接相同则不重试，直接把首因抛出。抓取失败分四类可见：非微信主机→抓取前就拒；连接中断→502 并提示出口网络；
  微信回 4xx→带上状态码与错误体片段；回 200 但不是图片→说明回来的是 HTML/JSON/空体。
  取回的内容再按文件头确认是图片，避免把错误页当二维码显示。
- 「消息与事件接收 URL」被**不带参数**访问时必须回一行纯文本，不能抛 `MissingServletRequestParameterException`：
  必填 `@RequestParam` 会让每次探测产生一整段 ERROR 栈（容器健康检查原先正是打这个地址，每 30 秒刷一条，把同期真正要查的错误淹掉）。
  健康检查改打独立的 `GET /wx/health`；缺参/无映射各有一行日志的处理器；`BizException` 现在也记一行 WARN（以前界面显示中文原因、日志完全空白）。
- GET URL 校验的签名参数名随加解密方式不同：普通模式放 `signature`，安全模式放 `msg_signature`（与 POST 推送同名）。
  只读其中一个，另一种模式下签名恒校验失败、开放平台后台就保存不了回调地址，所以两个都取。

## 6. 测试

```bash
mvn -B test          # 210 个用例：192 个无需中间件 + 18 个连本机 MySQL/Redis 的 E2E（详见 §1.2）
```

| 测试类 | 锁住的东西 |
| --- | --- |
| `WeChatMsgCryptTest` | 安全模式签名与加解密闭环（回调 URL 校验、ticket 解密） |
| `WechatApiContractTest` | 每个微信接口的路径、鉴权参数放哪、请求体字段名与类型（`auditid` 必须是 number，不是字符串）；图片抓取主机白名单（放行 `*.qpic.cn`/`*.qlogo.cn`/`*.weixin.qq.com`/桩主机，CDN 的 http 改写成 https，`mmbiz.qpic.cn.attacker.com` 与 `169.254.169.254` 一律拒） |
| `WechatClientFactoryTest` | 微信接口域名优先级：库里的 api_base 覆盖 `WECHAT_API_BASE`，留空才回落 |
| `WeChatCallbackDispatchTest` | 回调分发：ticket/授权/解除授权各落到哪一层，签名不过仍回 success，事件表留档字段 |
| `ComponentTokenLifecycleTest` | component_access_token 生命周期：无 ticket 的 409、锁竞争、61023 判定 ticket 过期 |
| `AuthorizerTokenRotationTest` | 令牌链：refresh_token 轮换必须回写、失效只重试一次、61023 判定授权失效 |
| `ReleaseWorkflowTest` | 发布链路状态机：ext_json 组装、auditid 落库、AUDIT_PASS/GRAY/REVERTED 流转 |
| `ReleaseIcpStatusTest` | 备案 1024~1031 前置条件按位解码（基本信息/类目/认证），缺项一次性列全 |
| `MiniProgramSettingMappingTest` | 微信下划线字段 ↔ 界面驼峰字段（域名六类、invalid/noIcp、certicates 拼写）；multipart 出站带 Content-Length 而非 chunked；普通方式注册时改名本地拦下；小程序码代取（落库链接优先、非微信主机零请求直接拒、回传 data URL、非图片/空体/4xx/连接中断四类失败各有可读原因、ticket 过期自动刷新资料换新链接重试）；本地副本（有副本时零微信请求、取回后写库、`refresh=true` 覆盖副本、链接变了或超过 7 天才回源、回源失败退回报副本并带 `staleReason`） |
| `MiniProgramPrivacySettingTest` | 隐私保护指引请求体（privacy_ver / setting_list / owner_setting） |
| `PlatformDomainServiceTest` | 第三方平台域名白名单：`;` 拼接的裸主机名、两类接口字段名不同（`wxa_server_domain`/`wxa_jump_h5_domain`）、写后必须再 `get`、端口与空清单本地拦住 |
| `PlatformConfigSerializationTest` | 密钥不进 toString/日志/响应，PUT 的明文密钥能正常绑定 |
| `PlatformConfigServiceTest` | 单行配置不自动补默认域名（否则 `WECHAT_API_BASE` 失效）、空串能清掉覆盖项、缺字段保持原值 |
| `PlatformWebContractTest` | Controller 参数绑定、微信 errcode 与 HTTP 状态的映射、分页信封、平台密钥只回掩码 |
| `PlatformEndToEndTest` | 从扫码授权到发布/灰度/回退的完整链路（桩微信 + 真库表），含操作日志按 `commit`/`release` 等微信接口名精确检索、撤回与加速审核（含不传 `auditId` 的回落）、小程序码在真 MySQL 上的 `MEDIUMBLOB` 字节往返（`V5` 迁移建表 + 第二次读取零微信请求 + `refresh=true` 才回源） |

已在本机连真库、连真微信域名验证过（2026-11-17）：登录、凭据保存且只回掩码、安全模式的回调 URL 校验与票据推送
（含签名不符仍回 `success`、`/wx/callback/{appid}` 代收路径）、健康检查真实请求 `api.weixin.qq.com`
（假 appid 返回 `40013`，说明出网与参数拼装都对）、`ticket` 落 Redis/MySQL、后端生产镜像构建并以容器跑通健康检查。
同日还用本地桩服务实测了微信接口域名的三级行为：`api_base` 为 NULL 时 `api_component_token` 打到 `WECHAT_API_BASE`
指向的地址、库里填覆盖项后改打覆盖地址、提交空串能把覆盖清回 NULL；`V2` 迁移在「已有旧默认值」的库上单独跑过，
列变为可空且旧值被清空。

前端另用一份返回真实响应形状的本地桩（`/api`、`/wx` 按 vite 代理到 8080）在浏览器里逐页走查（2026-11-17）：
发布工作台在 `AUDIT_PASS` 时才放开「发布上线/灰度发布」、在 `GRAY` 时才放开「结束灰度/取消灰度」，
点「发布上线」确实发出 `POST /api/authorizers/{appid}/publish` 并把状态刷成 `PUBLISHED`（按钮随之全部置灰）。
走查抓出并修掉四个只有真数据才会暴露的问题：草稿箱「创建时间」显示 `1970-*`（微信的 `create_time` 是秒级时间戳，
`formatDateTime` 按毫秒解析，已改为小于 `1e11` 的数字一律当秒）、运营概览「平台启用状态」直接显示裸 `1`
（`status` 是 0/1，`authState` 映射表里没有，新增 `platformState` 映射为「已启用/未启用」）、
操作日志的检索提示写着 `COMMIT / AUDIT_SUBMIT / PUBLISH` 而库里真实动作名是小写的微信接口名
（`commit / submit_audit / release`，该提示是精确匹配输入，照它搜必然零结果，已改文案并由 `PlatformEndToEndTest` 锁住）、
发布工作台的「查看当前发布进度」对所有非 `AUDIT_PASS` 状态一律弹「当前发布单尚未审核通过」并跳回审核步骤
（已上线的发布单也被告知没过审），现改为按是否已过审分流：已过审直接带到发布/灰度或线上版本步骤并提示真实状态，
未过审才给出对应状态的原因提示（`INIT`→上传代码、`COMMITTED`→提交审核、`AUDITING`/`AUDIT_REJECT`→审核结果）。

再把后端 72 条路由与前端 `src/api` 的调用逐条比对（脚本解析 `@*Mapping` 与 `get/post/put/del` 的模板串），
差异只有「`experience-qrcode` 走 `<img>`、`/wx/*` 由微信与浏览器跳转、`avatar`/`to-template` 用了别的辅助函数」这类匹配器误报，
真正缺入口的只有一条：`POST /api/authorizers/{appid}/audit/speedup`（前端函数早已写好但没有任何界面调用它）。
现在「审核结果」面板补了「加速审核」按钮——只在审核中可点、二次确认（会消耗加急额度），走查确认它发出
`POST /api/authorizers/{appid}/audit/speedup?auditId=278820600` 并回读最新审核记录，审核转成「审核通过」后与「撤回审核」一起置灰。
后端同时改成不传 `auditId` 时回落到最近一次提审记录（原先空值会进到 `Map.of` 直接抛 500）。
顺带用同名 `.md` 文档核到 `commit`/`submit_audit`/`release`/`speedupaudit` 全归属权限集 `18`（旧文档写成 `1`），
界面因此新增「授权权限集」展示并在缺 `18` 时告警；`auditid` 的 number 线格式由 `WechatApiContractTest` 锁住。

再往后的域名与成员这一轮，按 openApi 文档逐条核对并把结论写进测试（2026-11-17）：

- **第三方平台账号自身的域名白名单**：`modify_wxa_server_domain` / `modify_wxa_jump_domain` 只能用
  `component_access_token`，域名是 `;` 拼接的裸主机名（带协议头、路径或端口都会被判无效），两类接口的字段名不同
  （`wxa_server_domain` / `wxa_jump_h5_domain`），响应只有 `published_*` / `testing_*` / `invalid_*` 三份清单，
  上限分别是 1000 与 300 个。文档写明「授权给第三方代开发的小程序，其域名只能取自这里」，
  所以「平台配置」页新增面板作为小程序域名配置的前置步骤，`/api/platform/domains/{kind}` 暴露读写与校验文件。
- **业务域名的读取入口只有一个**：`setwebviewdomain` 的响应只有 `errcode`/`errmsg`，清单必须走
  `/wxa/get_effective_webviewdomain`（`mp_/third_/direct_` 三份合并去重），因此保存后要再读一次才能回填界面。
- **体验成员**：`memberauth` 的 `members` 是 objarray，元素形如 `{"userstr": "..."}`；按字符串数组解析会得到一串空值。
- **隐私指引**：`getprivacysetting` 的 `privacy_ver` 必填（1 现网 / 2 开发，缺省按 2 发送），
  `setprivacysetting` 在 `privacy_ver=1` 时不接受 `setting_list`，开发版则必须有 `setting_list`。
- **`getvisitstatus` 是「服务状态」而不是「是否允许被搜索」**：`status` 0 已暂停服务（含违规暂停）/ 1 未暂停，
  读不到时界面显示「服务状态未知」，不再默认成正常。暂停/恢复走 `/wxa/change_visitstatus`，
  字段只有一个 `action`（`close` 不可见 / `open` 可见，别的取值微信会拒绝），响应只有 `errcode`/`errmsg`，
  所以后端 `setVisitStatus` 写完一定要再读一次 `getvisitstatus` 作为返回值，界面才知道有没有真的生效；
  因违规被微信暂停的小程序用 `open` 恢复不了，界面上的确认框里直接写明了这一点。
- **审核状态有 5 个取值，不止「审核中/通过/驳回」**：`get_auditstatus` 与 `get_latest_auditstatus` 的 `status` 是
  `0 通过 / 1 不通过 / 2 审核中 / 3 已撤回 / 4 审核延后`，`reason` 在 `status=1` 是拒绝原因、在 `status=4` 是延后原因（驳回还会带 `screenshot` 的 media_id 竖线串）。
  `4` 的含义是微信推迟了结论、审核单仍在队列里，所以它既不是驳回也不能当「已结束」：本地发布单继续留在 `AUDITING`，
  界面继续轮询、撤回仍可用，只有「加速审核」不放开（文档没有对延后单加急的依据）。
- **提审的 `address` 必须是当前代码包里存在的页面**：`get_page` 返回的就是这份清单（`page_list`，字符串数组），路径填错微信直接拒。
  提审面板的页面路径因此从 `get_page` 取候选，但仍允许手输（子包页面与带 query 的写法不一定出现在清单里），
  且「不在清单里」只给橙色提示不拦提交——清单可能因为没上传代码或缺权限集 18 而读不到，这时不能把商家的提审按钮堵死；
  面板是 `v-show` 常驻的，上传新代码后不会重新挂载，所以还提供了「重新读取页面清单」。
- **`getversioninfo` 要 POST 一个空 JSON，且没有对应版本时整个对象缺席**：不传 body 微信回 `44002 empty post data`；
  `exp_info` / `release_info` 只在真的有体验版 / 线上版时才出现，`exp_time` / `release_time` 是**秒级**时间戳
  （原样透传给界面就是 `1640762988`）。所以后端把两者归一成 `{version, desc, at}`、没有对应版本时显式返回 `null`，
  界面才能区分「微信侧确实没有线上版」和「这次没读到」；发布工作台步骤二/步骤六直接显示它，
  本地发布单记成已上线而微信侧读不到线上版（或反过来）时给橙色提示——灰度中不做这个比对，线上与灰度本来就是两个版本。权限集 18。
- **分阶段发布的真实状态只能读 `getgrayreleaseplan`**：响应把计划包在 `gray_release_plan` 里，
  `status` 是 `0 初始 / 1 执行中 / 2 暂停中 / 3 执行完毕 / 4 被删除`，另有 `gray_percentage`、`create_timestamp`（秒）、
  `support_debuger_first`、`support_experiencer_first` —— 注意最后这个是**微信自己少写了一个 g**，按 `support_debuger_first` 拼写请求与解析才拿得到值
  （发出去时同理，`grayrelease` 的请求体也是 `support_debuger_first`）。`grayrelease` 本身的响应**只有 `errcode`/`errmsg`**，
  不回比例，所以「灰度到多少了」必须另外读计划。本地发布单的 `GRAY` 只证明「我们点过一次灰度」：
  商户可以直接在小程序后台结束或取消灰度，那样没人来改这条记录，界面上的「灰度发布」会被永久置灰且刷新也不恢复，
  因此 `GET /api/authorizers/{appid}/gray-release` 在读到确定性结论（`3`/`4`/没有计划）且本地仍是 `GRAY` 时顺手把本地改成 `PUBLISHED` 并回 `localCorrected`，
  界面收到就重拉发布单。权限集同样是 `18`。
- **`modify_domain` 的域名必须带协议**：request/upload/download 用 `https://`、socket 用 `wss://`、udp/tcp 用
  `udp://`/`tcp://`（此前界面把协议头剥掉再提交，微信会判成无效域名）；`add` 只追加、`set` 整份覆盖，
  界面对三种 action 分别只发差异清单，无差异时不发请求（每月 50 次额度，微信对无变化的提交回 `85303`）。
- **小程序域名页会先比对平台白名单**：提交前把要发的域名按小写主机名（忽略协议、路径、端口）与
  `modify_wxa_server_domain` / `modify_wxa_jump_domain` 读到的 `published ∪ testing` 比对，不在里面的直接拦下并点名，
  因为这种提交微信必然拒绝、还白占每月 50 次额度；白名单读不到时（凭据没配）跳过比对、不拦正常操作。
  `delete` 不参与比对——它发的本来就是已存在的域名，拦等于拦清理（商户自己在小程序后台配的 `direct_` 业务域名往往并不在平台白名单里）。
  浏览器实测：`add` 一个 `api.blocked.example.org` 只弹提示、`uistub-access.log` 里没有对应 PUT；
  改成 `API.NEW.Example.org/activity/list` 后 `set` 发出
  `{"action":"set","type":"request","domains":["https://api.chunfeng.example.org","https://API.NEW.Example.org"]}`（大小写原样保留、路径被去掉）；
  同一个不在业务域名白名单里的 `https://m.example.org`，`set` 会被点名拦下、`delete` 则正常发出
  `{"action":"delete","domains":["https://m.example.org"]}` 并把清单刷成剩下的那条。

浏览器侧用同一份桩逐页验证了这些改动：白名单面板「添加 → 提交」（勾上「同时修改全网发布版」后）发出
`{"action":"set","domains":["api.chunfeng.example.org","Api.New.Example"],"modifyPublishedTogether":true}` 并同步了全网发布版清单；
小程序域名页在无改动时弹「没有检测到变更」、新增时只发 `{"action":"add","type":"request","domains":["https://api.new.example.org"]}`、
删除时只发被移除的那条；「发布状态」页的服务状态标签读到 `1` 显示「服务正常」；体验成员列表能显示 `userstr` 而不是空行。

镜像级整栈验证（2026-11-17）：`docker compose build api` 与 `build web` 都重新跑通（`node:24-alpine` 这次拉到了，
`npm ci` + `npm run build` 在容器里完成），再用临时环境变量（不落地 `deploy/.env`）加一份只放开 18085/18086 端口的
override 起 `mysql/redis/api/web`（不起 caddy）：Flyway 在新库上顺序跑完 3 个迁移、建表 13 张；浏览器里打开生产包
用 `admin/admin123` 登录，跳到运营概览并从真实后端取回状态；新增的 `/api/platform/domains/{kind}` 与 `/confirm-file`
在未配凭据时回 `409 第三方平台凭证未配置完整`、`kind` 写错回 `400 域名类型只能是 server（服务器域名）或 jump（业务域名）`，
说明这两个接口确实进了镜像。`web` 容器单独起会因 `proxy_pass http://api:8080` 解析不到 `api` 而直接退出——
这是 nginx 在加载配置时就解析上游主机名的行为，部署时 `web` 必须与 `api` 同栈。验证完 `down -v` 已清掉这套临时容器与卷，开发栈不受影响。
（这一轮之后 compose 已删掉 caddy 服务，TLS 改由宿主机 nginx 终止、web 只绑 `127.0.0.1:8088`，见 §1-4；当时那份只为绕开 80/443 的
端口 override 不再需要。）

最后一轮补的是「授权恢复」这条链路（2026-11-17）：后端 `AuthorizationService.createAuthSession(bizAppid)` 早就支持把链接锁到某一个小程序
（`ComponentApi.authUrl` 拼 `biz_appid`，文档写明指定后「只能是该 appid 的管理员进行授权，其他用户扫码会出现报错」），
前端 `createAuthSession({ bizAppid })` 也留了这个参数，但没有任何界面会传——缺权限集 `18` 的告警只会让管理员「重新授权」，却没有入口。
现在三处打通：小程序详情页头部新增「重新授权」，发布工作台缺 `18` 的告警里加同名按钮，两者都跳 `/authorizers/new?appid=…`；
扫码授权页读 `route.query.appid`，带 appid 进来就自动生成锁定链接，同时给出「不锁定，生成通用授权链接」的回退（一次要给多个账号授权时用）。
`PlatformEndToEndTest` 补了断言：传 `bizAppid` 时 `authUrl` 与 `mobileAuthUrl` 都含 `biz_appid=`，不传时不含。
浏览器实测（桩后端）：点告警里的「重新授权」后落地页链接是 `…&auth_type=3&biz_appid=wx0a1b2c3d4e5f6a7c`，
`uistub-payloads.log` 记录 `POST /api/authorizers/auth-sessions {"bizAppid":"wx0a1b2c3d4e5f6a7c"}`，点「不锁定」后重新生成的链接里没有 `biz_appid`。
踩到一个 Element Plus 的坑：`el-alert` 一旦提供默认插槽就不再渲染 `description` 属性，所以那句告警文案必须搬进插槽，否则加上按钮的瞬间说明文字就消失了。

接着把「权限集」从口头约定变成可选参数（2026-11-17）：授权链接支持 `category_id_list`，文档写明多个权限集 id 用中竖线隔开、
不传则默认拉取当前第三方账号已全网发布的权限集列表，而一旦指定，管理员就只能授权所选这几项。
后端 `POST /api/authorizers/auth-sessions` 收 `funcScopeIds`，`AuthorizationService` 校验正整数并去重后拼成 `18|1` 交给
`ComponentApi.authUrl` / `mobileAuthUrl`（`|` 按 URL 查询串规则编码成 `%7C`）；界面在「扫码授权」页的二维码上方给一个多选下拉，
默认留空并写明「指定后管理员只能授权所选这几项」，所选不含 `18` 时当场提示发布链路会报 `48001`。
`WechatApiContractTest` 锁住不传时链接里没有 `category_id_list`、传了则结尾是 `…&category_id_list=18|1`；
`PlatformEndToEndTest` 额外断言 `funcScopeIds:[0]` 直接回 `400` 且不会生成链接。
这一轮 E2E 还抓到一个真实缺陷：`mobileAuthUrl` 加了 `categoryIdList` 形参却忘了拼进查询串，于是 PC 链接尊重所选权限集、
手机 H5 链接却仍然授权全部权限集——两个入口行为不一致在真实凭据下才会暴露，参数没被使用也不会编译报错。
浏览器实测（桩后端）：勾 `18` 后链接是 `…&biz_appid=…&category_id_list=18`，再勾 `1` 变成 `…&category_id_list=18%7C1`，
删掉 `18` 后回到 `…&category_id_list=1` 并弹出「所选权限集里没有 18」的提示。

最后一轮把「服务状态」从只读改成可写（2026-11-17）：此前界面只能看到「服务正常 / 已暂停服务」，第三方平台后台对线上可用性没有任何处置手段，
出问题只能让管理员自己进小程序后台点。新增 `POST /api/authorizers/{appid}/visit-status`（请求体 `{"action":"close"|"open"}`）
→ `ReleaseService.setVisitStatus`：先校验 `action` 只认这两个值（其他取值一个微信请求都不发，直接 `400`），调用 `change_visitstatus`
之后再读一次 `getvisitstatus`，把重读到的状态作为返回值——这个写接口只回 `errcode`/`errmsg`，不重读就等于没确认。
「发布状态」页的标签旁据此给「暂停服务」/「恢复服务」按钮，都带二次确认，恢复的确认框里写明「若因违规被微信暂停，这里恢复不了」；
状态读不到（`null`）时按钮不出现，避免对着未知状态做破坏性操作。测试补了 3 例并在桩里补了 `change_visitstatus`：
`ReleaseWorkflowTest` 断言发出的是 `/wxa/change_visitstatus` 且请求体 `{"action":"close"}`、随后必须紧跟一次 `/wxa/getvisitstatus`，
非法 action 时 `MockWebServer` 收到 0 个请求；`PlatformWebContractTest` 锁 `{"action":"close"}` 能绑进 `VisitStatusRequest`；
`MockWeChatServer` 的服务状态改成有状态（`close` 之后 `getvisitstatus` 回 0），于是 `PlatformEndToEndTest` 能一路断言
「写 → 重读为 0 → 非法 action 回 400 → 恢复为 1」。浏览器实测（桩后端）：点「暂停服务」弹「暂停后会员立刻打不开该小程序，直到恢复为止。确定暂停？」，
确定后标签变「已暂停服务（主动暂停或违规暂停）」、按钮换成「恢复服务」、提示「已暂停服务」，`uistub-payloads.log` 里记的是 `{"action":"close"}`；
在恢复的确认框里点「取消」不发请求、标签不变，点「确定恢复服务」才发出 `{"action":"open"}` 并回到「服务正常」。
后端 170 例全绿（原 167 + 本轮 3），前端 `vue-tsc` + `npm run build` 通过。

还有一处是「等审核」只能靠人工：审核要 1-7 个工作日，而微信侧的审核结果推送（`MiniProgramAudit` 事件）字段名在文档站抓不到可靠原文，
本轮没有凭猜测去解报文，改为把「主动查询」自动化——`AuditStatusPanel` 只要审核单处于「审核中」就每 30 秒调一次 `POST /audit/refresh`，
页面切到后台（`document.hidden`）时暂停计时、回来后接着倒数，出结果（状态从 2 变成 0/1）时提示「审核已通过，可以发布或灰度了」并 `emit('changed')`
让工作台重新推导步骤，自动轮询走静默分支不刷屏，「加速审核」「撤回审核」仍在；面板上给了开关，不想让页面一直问微信可以关掉。
浏览器实测（桩后端：第 1 次查询回「审核中」、第 2 次回「通过」）：进「审核结果」步骤后显示「21 秒后自动查询」并逐秒递减，
第一次查询状态仍是审核中、不弹提示，第二次（约 60 秒后）标签变「审核通过」、弹「审核已通过，可以发布或灰度了」、
「加速审核/撤回审核」置灰、自动查询那一行随 `pending` 消失，顶栏当前发布单与发布历史同帧刷新为审核通过；
`uistub-access.log` 里这一轮只多了 2 次 `POST /audit/refresh`（与 30 秒节奏一致，没有失控轮询）；
把开关关掉后提示变成「自动查询已关闭，需手动点「主动查询」」且倒计时冻结，此时点「主动查询」仍正常发出一次请求并提示「已向微信侧查询最新审核状态」。

自动轮询只认「审核中」是不够的：`get_auditstatus` / `latest_auditstatus` 的官方枚举是 `0 通过 / 1 不通过 / 2 审核中 / 3 已撤回 / 4 审核延后`
（文档同时说明 `reason` 在 `status=1` 时是拒绝原因、在 `status=4` 时是延后原因，原文用 `.../get_auditstatus.md` 抓到）。对着这份枚举逐处核对，改了三处：
`platform-web/src/utils/status.ts` 里 `'4'` 的标签写成了「审核前退款」，与审核毫无关系，改为「审核延后」；
`ReleaseService.applyAuditStatus` 缺 `case 4`，延后会落进 `default` 保持原状态，从 `AUDIT_PASS` 再查到延后时无法退回审核中，补为 `case 4 -> "AUDITING"`；
`AuditStatusPanel` 三处 `status === 2` 的判断把延后单当成「已结束」——自动轮询停掉、撤回置灰（而撤回恰是延后单唯一的主动出口），
改为 `delayed`/`awaiting`：轮询与撤回按 `awaiting`（2 或 4），「加速审核」仍只对 `status=2`（文档没有对延后单加急的依据），延后提示原样展示微信返回的原因；
顺带删掉 `watch(pending)` 的停表分支，计时器统一由 `pollable` 控制，避免延后单把轮询停掉。
同一轮还纠正了 `releaseStepMap` 与视图自身跳转逻辑的矛盾：`AUDITING`/`AUDIT_REJECT` 定位在「提交审核」，可结论与原因都在「审核结果」
（`AUDIT_REASON_STEP` 就是这么写的），而延后单的自动轮询面板正是在「审核结果」；`AUDIT_PASS` 定位在「审核结果」，可视图里发布动作完成后跳的是「发布 / 灰度」。
改为 `AUDITING: 3`、`AUDIT_REJECT: 3`、`AUDIT_PASS: 4`，让「按发布单状态自动定位」落在真正有结论或有待办的那一步。
测试补 `ReleaseWorkflowTest.auditDelayedStaysAuditing`（喂 `{"errcode":0,"status":4,"reason":"…"}`，断言记录是 4 且带延后原因、发布单仍是 `AUDITING`），
后端 171 例全绿（原 170 + 本轮 1），前端 `vue-tsc` + `npm run build` 通过。
浏览器实测（桩把审核单种成 `status=4`）：状态标签与提示都是「审核延后」并显示「微信返回的原因：主体经营范围与所选类目描述不一致…」，
倒计时继续跑且 `uistub-access.log` 在 30 多秒里自动发了 2 次 `POST /audit/refresh`（每次之后工作台重读详情与发布单），
「加速审核」置灰、「撤回审核」可点，定位显示「第 4 / 6 步：审核结果」；延后期间手动点「主动查询」只提示「已向微信侧查询最新审核状态」不误报结果；
桩在第 2 次查询改口返回通过后，弹「审核已通过，可以发布或灰度了」、标签变「审核通过」、自动查询那一行消失、两个按钮置灰，
定位同时跳到「第 5 / 6 步：发布 / 灰度」。
手动查询也不再和自动轮询叠在一起：倒计时剩 19 秒时点「主动查询」，请求发出后倒计时回到 29 秒再逐秒递减，不会出现「查完一秒又查一次」。

驳回截图这一轮补了 3 例（后端 174 例全绿，原 171 + 3）：`ReleaseWorkflowTest` 里把 `caller.authorizerBinary` 也接上真实 HTTP，
一条断言 `screenshot: "aaa|bbb"` 会拆成两次 `POST /cgi-bin/material/get_material`、请求体是 `{"media_id":"aaa"}`、
按 PNG 文件头给出 `image/png` 与 `data:image/png;base64,` 前缀；另一条断言没有 `screenshot` 字段时 `MockWebServer` 收到 0 个请求；
`PlatformWebContractTest` 锁 `GET /audit/screenshots` 的 `{mediaId, contentType, dataUrl}` 形状。
浏览器实测（桩后端把审核记录种成 `status=1` 并带两个 media_id）：进「审核结果」步骤后面板显示「审核不通过」与驳回原因，
下方出现「微信给出的驳回截图 2 张，点开可放大查看：」和两张已加载完成的缩略图（`naturalWidth` 非 0），
点第一张弹出大图查看器（含上/下张、缩放、关闭按钮，关闭后遮罩移除），整个页面只发了 1 次 `GET /audit/screenshots`——
截图不是轮询项，只有驳回时才按需取一次；此时自动查询那一行不出现、加速与撤回都置灰，符合驳回态不该再问微信。

灰度计划这一轮补了 3 例（后端 177 例全绿，原 174 + 3）：`ReleaseWorkflowTest` 里 `GET /wxa/getgrayreleaseplan` 真的发出去一次，
`gray_release_plan` 的 `status=1` 归一化成「执行中 / actionable」、`support_debuger_first` 按微信的错拼读成 `supportDebuggerFirst`、
`create_timestamp` 转成本地时间；`status=3` 与响应里没有计划时都不再 actionable。顺带暴露了一个更值钱的问题：
E2E 的 `MockWeChatServer` 把该响应自造成为 `{grayinfo:{...}}`、并把 `grayrelease` 的响应写成会回 `gray_percentage`，
测试对着假形状一直是绿的——照官方文档改成 `gray_release_plan` 与「只回 errcode/errmsg」后，`PlatformEndToEndTest` 立刻失败并改判到真实字段上。
纠正本地卡住状态也有断言：本地 `GRAY` + 计划 `status=3` 时读一次就把发布单改成 `PUBLISHED`（备注写明是微信侧已结束），
而计划 `status=1` 时不碰本地记录。浏览器实测（桩：本地 `GRAY` + 计划已执行完毕）：进「发布 / 灰度」步骤即回显
「微信侧灰度计划：执行完毕 · 100% · 体验成员优先 · 创建于 2025-11-13 02:13:20」，同时本地发布单被纠正，提示变成「线上已是最新版本」；
把状态推回 `AUDIT_PASS` 再点「灰度发布（1%）」，计划变成「执行中 · 1%」、结束/取消灰度同时可点，点「结束灰度」后回到「执行完毕 · 100%」且按钮复位。
版本信息这一轮总数不变（仍是 177 例）：`ReleaseWorkflowTest` 原本断言的是透传形状，改成断言归一化后的 `{version, desc, at}`，
并额外卡住两条容易复发的——`exp_time` 按秒解释（当成毫秒会落到 1970 年）、微信没返回 `release_info` 时 `release` 是 `null` 而不是空对象。
界面用桩看过三种状态：步骤二显示「微信侧当前体验版：1.5.0 · 上传于 2026-11-16 20:12:05 · 活动报名优化」；步骤六在本地记为已上线而微信侧没有线上版时
亮出「本地发布单显示「已上线」，但微信侧读不到线上版本…」，让桩的 `publish` 把体验版提升为线上版后点「刷新状态」，该行变成「微信侧线上版 1.5.0 /
微信侧发布时间 2026-11-17 18:10:07」且提示消失。
提审页面清单这一轮也没有新增用例（177 例不变），界面用桩验证过三点：步骤三顶部显示「代码包里有 4 个页面，页面路径可直接选」，
输入 `pages/act` 出现候选 `pages/activity/list`，输入 `pages/ghost/page` 亮出「当前代码包里没有 pages/ghost/page 这个页面，微信会以「页面不存在」拒绝这次提审…」。
顺带抓到一个纯视觉回归：`el-autocomplete` 上的 class 会被 Element Plus 转交给内层 `el-input`，写在 `.audit-submit__page` 上的 `flex: 2`
从来没命中真正的 flex 子项，页面路径列固定撑到 368px、同排 query 输入框被挤到 22px；改成从行容器 `:deep(.el-autocomplete)` 命中根节点
（`flex: 2` + `min-width: 0`）后实测变成 260 : 130。

改名与头像这一轮补了 12 例（后端 189 例全绿，原 177 + 12）。头像的失败**不是**图片超限：`multipartUploadSendsContentLength`
断言出站请求头里 `Transfer-Encoding` 为 null、`Content-Length` 等于实际 body 字节数，同时校验 boundary 出现在 body 首尾、
`Content-Disposition: form-data; name="media"; filename="avatar.png"` 与 `Content-Type: image/png` 都在。
这个缺陷能活到生产，是因为 `MockWeChatServer` 和本机桩对 chunked 与 Content-Length 一视同仁——186 条绿灯测试全部接受 chunked，
所以只能从请求头守住；真正定位靠的是把出站字节原样打到本地 socket 上，实测改前是 `Transfer-Encoding: chunked`、改后是
`Content-Length: 1573062`。改名侧新增 `setNicknameBlockedForNormalRegister`（`register_type=0` 时**一个微信请求都不发**，
错误文案指向微信公众平台）与 `basicInfoExposesApiRenameCapability`（0 → `apiRenameSupported=false`，没刷新过资料 → null 不误伤），
`WechatApiContractTest` 把 91xxx/头像侧错误码的中文说明和「未映射码回落原文」一起锁住。
浏览器实测（桩后端可切 `register_type`）：`register_type=0` 时改名弹窗顶部亮出「该小程序不支持通过第三方平台改名…」且「提交修改」
按钮 `disabled`；切到 `6` 后提示消失、提交后桩收到 1 次 `PUT …/nickname`，页面只弹一条「已提交微信审核，审核单号 300001」；
头像走 `PUT …/avatar` 同样只弹一条「头像已更新，微信侧生效可能有延迟」，不再出现重复 toast。

小程序码这一轮补了 6 例（后端 195 例全绿，原 189 + 6）：`qrcodeUsesStoredLink` 断言用的是落库链接、请求里没有 `Referer`、
回传的是能解回原字节的 `data:image/png;base64,`；`qrcodeRejectsForeignHost` 把链接换成 `http://169.254.169.254/latest/meta-data/`，
靠 `getRequestCount()` 为 0 证明**抓取前**就拒了（这类守卫只有请求数能守，桩本身照收）；
`qrcodeRequiresHttpsForWeixinHost` 证明微信域名的 https 要求不跟着 `api_base` 的协议放松；
另有老字段兜底、两处都缺链接时提示刷新资料、取回非图片时点名三条。
浏览器实测（桩可切 `ok`/`fail`）：成功态 `<img>` 的 src 是后端给的 data URL、`complete` 为真、渲染 148×148，点开走 `el-image` 预览；
失败态占位框只写「小程序码取图失败」，完整原因单独一行（实测 230px 宽、2 行换行，没被 150px 的框挤成一列一个字），
图片节点消失、点「重新取图」切回 ok 后立刻恢复，两种状态都是 0 条 toast。
桩只能证明本站这条链路（路由 → 服务 → 抓取 → data URL → 界面），
「服务端不带 Referer 时微信确实回原图」仍要等真实凭据与线上域名才能确认。

线上反馈「页面显示小程序码取图失败」时贴来的日志却是 `Required request parameter 'timestamp' ... not present`——
这条**不是**取图失败：`Dockerfile` 的 `HEALTHCHECK` 每 30 秒 `curl /wx/callback` 不带任何 query，
而 `verify` 把 `timestamp/nonce` 声明成必填 `@RequestParam`，于是每 30 秒刷一整段 `未处理异常` 栈，
真正要查的那条错误反而淹在里面。更糟的是取图失败走 `BizException`，而 `GlobalExceptionHandler.handleBiz` 一行日志都不写，
前端取图又是 `SILENT`（不弹 toast），所以那次失败在日志里完全隐形。
本轮补了 7 例（后端 202 例全绿，原 195 + 7）：`PlatformWebContractTest` 锁住不带参数的 GET/POST 回一行纯文本且绝不进加解密、
`/wx/health` 探针可用、GET 校验只带 `msg_signature` 时也要认；`MiniProgramSettingMappingTest` 锁住非图片说清回来的是 HTML、
空体说清是空内容、4xx 带状态码与错误体、连接中断原样抛 `ResourceAccessException`（否则界面只剩「服务器内部错误: null」）、
以及 ticket 过期先刷新授权资料换新链接重试一次（新链接与旧链接相同则不重试，`getRequestCount()` 为 1 守住这点，
且重试成功后响应的 `url` 必须换成新链接，否则「复制图片链接」复制出去的还是过期 ticket）。
`showqrcode` 对过期 ticket 回 **HTTP 404 + 空响应体、不带 errcode** 这个形状，是本机直接 `curl` 真实
`mp.weixin.qq.com` 域名拿到的（无效 ticket 一律 404 空体），不是猜的。
桩侧两个新坑：测试用的 `RestTemplate` 必须像生产 `WechatClientFactory` 一样设读超时，否则连接池踩到服务端已关的连接时
用例会永久阻塞（`jstack` 卡在 `HttpURLConnection.getResponseCode`，实测冻住整轮近 6 分钟），
且错误状态响应要带 `Connection: close`；多请求用例别依赖顺序 `enqueue`，改按路径分派的 `Dispatcher`。
取图的真因仍要部署后看那一行新日志确认（`docker compose logs api | grep -E "业务异常|微信图片接口"`），
头号候选是过期 ticket——现在有自动刷新重试，界面若仍失败会直接写明是 404、连接不通还是内容不是图片。

第二次线上反馈是「小程序码仍然不能显示」，界面那行原因把真因直接指出来了：`图片链接的域名不是微信站点，已拒绝抓取：mmbiz.qpic.cn`——
**是上一轮自己加的抓取守卫把微信的自有图片 CDN 拦了**（当时只放行 `*.weixin.qq.com` 与 `api_base` 同主机，而现网 `qrcode_url`
是 `mmbiz.qpic.cn`，头像在 `qlogo.cn`，且文档示例里还有 `http://` 写法）。放行这两类 CDN 主机、并对 CDN 主机的 http 改写成 https
（同一 CDN 路径支持 https，改写比拒绝有用），比较副本链接时先归一协议。守卫的回归由 `WechatApiContractTest` 新增 2 例守住：
放行侧列全 `mmbiz.qpic.cn`/`wx.qlogo.cn`/`mp.weixin.qq.com`/桩主机，拒绝侧包含 `169.254.169.254`、
`attacker.example.com/mmbiz.qpic.cn/…`、`mmbiz.qpic.cn.attacker.com`（只比后缀结尾就会被绕开的那一类）与 `file:///etc/passwd`。

按「后端获取小程序码并保存至本地，向前端返回后端保存的小程序码」把存储做进了链路：`V5__authorizer_qrcode_image.sql` 建
`wechat_authorizer_qrcode`（主键就是 `authorizer_appid`，图片是 `MEDIUMBLOB`）配 `AuthorizerQrcodeMapper`，
`qrcodeImage(appid, refresh)` 变成三种走向——库里有可用副本且链接没变、没过期就**直接回副本（一个微信请求都不发）**；
`refresh=true`（界面「重新取图」）或链接变了/副本超过 7 天才回源并覆盖；回源失败但库里有副本时仍回副本，把失败原因放进
`staleReason`。存库而不是存容器磁盘的理由写进了 §5：`api` 服务没挂卷，落盘的文件每次重新部署就没。
前端 `AuthorizerQrcode` 多出 `source`/`savedAt`/`staleReason` 三个字段，界面上是「后端保存于 …」/「刚从微信取回，存于 …」
一行说明，副本兜底时额外一行橙色写明这次为什么没取到。
本轮补 8 例（202 → 210，其中 E2E 17 → 18）：`MiniProgramSettingMappingTest` 5 例守读/存/覆盖/回源条件/回退，
`PlatformEndToEndTest` 那 1 例是唯一能证明 **MyBatis 的 `byte[]` 往返与 `V5` 迁移在真 MySQL 上成立**的用例——
单元用例里的 Mapper 是内存 `Map` 替身，字节有没有被截断、`map-underscore-to-camel-case` 认不认这张表都守不住；
它同时断言第二次读取时桩微信的 `showqrcode` 调用计数不涨（只数 `showqrcode`，因为那台桩是所有用例共用的，
`TicketPushWatchdog` 之类的后台任务也会在 2 分钟后开始留痕）。
浏览器实测（桩后端按后端新形状返回，三种状态都看过渲染 DOM）：默认态 `<img>` 148×148、`complete` 为真、下一行是
「后端保存于 2026-11-20 18:16:55」（`LocalDateTime` 无时区串被 `formatDateTime` 正常解析，不是 `—`）；
点「重新取图」后 `uistub-access.log` 里是 `GET /api/authorizers/{appid}/qrcode?refresh=true`，文案换成「刚从微信取回，存于 …」；
把桩切成回源失败后图片仍在，下方多一行 `rgb(230, 162, 60)`（即 `--el-color-warning`）的失效原因。
这一轮 `take_screenshot` 仍拿不到画面（内嵌浏览器 surface 是 hidden），所以证据只有尺寸/颜色/文本这类渲染值与访问日志；
`click` 有一次落在刚渲染出的 `el-button` 上没发出请求，改用 `evaluate_script` 派发才看到 `?refresh=true`，结论以日志为准。
仍然没被证明的：服务端带着放行后的白名单去请求**真实** `mmbiz.qpic.cn` 直链能否取回原图（要部署后那一次真实请求，
`docker compose logs api | grep 业务异常`），以及首次部署时 `V5` 迁移在既有库上跑不跑得干净。

仍只能等真实凭据与备案域名才能验证：真实 `component_appid`/`component_secret` 换取 token、出口 IP 白名单（缺失报 `40164`）、
公网 https 备案域名的微信侧校验、已备案小程序才允许发布、宿主机 nginx 的证书链与 `/wx/` 透传（compose 已不含 caddy）。
第三方平台的加急额度余量也没有可查接口，用超了只能等微信返回错误码。

## 7. 接真实凭据后的上线验收清单

本地已经用桩与 `MockWeChatServer` 覆盖了下面每一步的**代码分支**，缺的只是真实微信侧的答复。拿到凭据与备案域名后照顺序跑，
任一步不过就别往下走——后面全部依赖前一步的令牌与授权状态。

0. **部署自检**：`curl https://<域名>/wx/health` 回 `ok` —— 同时证明 api 镜像是重新构建的、宿主机 nginx 的 `/wx/` 透传正常；
   `docker compose logs api` 里不该再有每 30 秒一条的 `timestamp` 缺参异常，业务失败现在都是一行 `业务异常 ... message=...`。
   若小程序码报「后端没有这个接口」，就是 `docker compose build api` 没跑或没生效。
1. **凭据落库 + 票据**：按 `deploy/docker-compose.yml` 的 `environment` 列出哪些要填，在 `deploy/` 下本地新建 env 填真实
   `component_appid`/`component_secret`/消息加解密密钥与 `aes_key`（仓库里不放 env 文件，也不要把密钥抄进任何提交物），
   起整栈后看「平台配置」页 `secretSet/messageTokenSet/aesKeySet` 全为真、`ticketFresh` 为真。
   票据 20 分钟内没推来就是「授权事件接收 URL」在微信后台填错或回调不通，`/api/platform/health` 的 `configReady` 会是 false。
2. **出口 IP 白名单**：「平台配置 → IP 白名单」填部署机公网 IP（容器出口 IP，不是宿主机内网 IP）。
   漏填时**所有**接口调用报 `40164`，`/api/platform/health` 的 `componentTokenValid` 也会是 false。
3. **第一个真实调用**：发布工作台头部点「刷新令牌」→ 看步骤六「微信侧线上版」与步骤二的体验版行（这两个数就是直接问微信要来的）。
   这一步同时验证 `component_access_token` → `authorizer_access_token` 链路和权限集 18；报 `48001` 说明商家授权时没勾上代码类权限集，
   走「重新授权」补齐即可。
   同一时刻在「基本信息」页能确认小程序码这条链：先点「刷新资料」（`qrcode_url` 只来自 `api_get_authorizer_info`），
   首次进页面后端去 `mmbiz.qpic.cn` 取原图并存进 `wechat_authorizer_qrcode`，界面写「刚从微信取回，存于 …」；
   再进一次该页应写成「后端保存于 …」，且 `docker compose logs api` 里没有新增取图记录——说明读的是库里的副本。
   出现橙色那行失效原因时按它分流：`404`/空内容＝落库链接里的 ticket 过期（会自动刷新资料重试一次）、
   连接不通＝出口网络或 IP 白名单、`不是图片`＝回来的是错误页而不是二维码。
4. **上传代码 → 体验版**：选模板提交代码包，然后点步骤二的「刷新二维码」拿到真实体验版二维码，扫码进体验版；
   再把 `GET /wxa/get_page` 的清单与模板实际页面对一遍——提审面板的候选就来自这里，清单为空说明这次 commit 没成功。
5. **提审 → 审核 → 发布**：提交审核后界面每 30 秒自动查一次；`status=4`（审核延后）会留在「审核中」继续轮询，不要当驳回处理。
   过审后点发布，若报未备案相关错误，先看「备案状态」面板的解码结果，缺哪一项补哪一项。
6. **灰度与回退**：灰度发布后「发布 / 灰度」面板应回显「执行中 · N%」（比例只来自 `getgrayreleaseplan`，微信的 `grayrelease` 响应里没有它）；
   结束灰度后回到「执行完毕 · 100%」。若商家直接在小程序后台收尾，本地 `GRAY` 会在下一次读计划时被纠正成已上线，界面会写明原因。

跑通这 6 步即认为「第三方平台网站可管理开发与发布」在真实环境成立；步骤 1–3 是纯凭据问题，4–6 才需要真的代码包与备案完成的主体。
