# 平台管理员初始化与控制面认证

平台管理员是控制面身份，与租户业务用户分开存储。平台账号、会话和安全审计只写入控制面数据库的 `iam`、`audit` Schema。

## 首次初始化

1. 确认已配置控制面数据库连接。
2. 首次初始化时执行一次数据库迁移并生成 15 分钟有效的一次性凭据：

```bash
ZTX_DB_MIGRATE_ON_START=true ./gradlew :apps:server:run --args='--ztx.profile=dev --ztx.platform-admin-init=true'
```

命令仅在平台尚无管理员时成功。凭据只输出到命令行一次，请通过安全渠道交给部署人员；不要粘贴到工单、聊天记录或应用日志。

3. 启动 API Server：

```bash
./gradlew :apps:server:run --args='--ztx.profile=dev'
```

4. 打开 Web 工作区中的 `/platform/login`，切换到“初始化平台管理员”，输入一次性凭据、管理员账号和密码。成功创建后凭据立即失效，再使用新账号登录。

密码至少 8 位，并同时包含字母和数字。系统不设置任何内置管理员账号或默认密码。

## 会话与安全策略

- 登录会创建服务端会话，浏览器仅持有随机不透明令牌；数据库只保存 SHA-256 摘要。
- Cookie 使用 HttpOnly、SameSite=Strict，最长有效期 8 小时；生产 Profile 默认启用 Secure。
- 退出会撤销当前会话；修改密码会撤销该管理员的其他活动会话。
- 同一来源 IP 与账号组合在 15 分钟内连续失败 5 次后限制登录 15 分钟。
- 登录成功/失败、初始化、退出、密码修改和 MFA 操作写入 `audit.platform_security_event`；不记录密码、初始化令牌或会话令牌。
- MFA 使用 TOTP（RFC 6238，6 位、30 秒周期）。先在平台控制台生成密钥并添加到认证器，再输入当前动态验证码确认启用。

## MFA 加密密钥

启用 MFA 前必须配置独立的加密密钥，至少 32 字节。推荐通过 Secrets 注入：

```bash
ZTX_MFA_ENCRYPTION_KEY='<至少 32 字节的随机密钥>'
```

也可在外部 YAML 的 `security.mfa-encryption-key` 中配置。生产环境必须妥善备份该密钥；更换密钥前需要先设计密钥轮换和重新加密流程，否则已有 MFA 密钥将无法解密。不要将真实密钥提交到 Git。

## API

- `POST /api/v1/platform/auth/initialize`
- `POST /api/v1/platform/auth/login`
- `POST /api/v1/platform/auth/logout`
- `GET /api/v1/platform/auth/me`
- `POST /api/v1/platform/auth/password`
- `POST /api/v1/platform/auth/mfa/enroll`
- `POST /api/v1/platform/auth/mfa/enable`

API Server 应通过 HTTPS 暴露；生产反向代理需保留安全 Cookie 和正确的客户端来源地址。
