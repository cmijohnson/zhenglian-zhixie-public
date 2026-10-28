# 英格云原生移动端架构与交付约定

## 1. 范围

本仓库同时维护两个原生员工客户端：

- `android-app/`：Kotlin、Jetpack Compose，`com.example.yingge`，最低 Android 9。
- `ios-app/`：Swift、SwiftUI，`com.example.yingge`，最低 iOS 16。

除 QQ 工作台外，员工可见功能应使用原生页面。QQ 只允许通过一次性 Web Session Ticket 打开当前服务器的受控工作台。组织管理、成单审核等管理员控制面继续使用 Web。

第二版设计稿是业务页面基线，第三版设计稿是深色模式、状态页、键盘、离线、AI 和 QQ 容器基线。客户端直接使用公司提供的 Logo 原图，不使用生成式重绘版本。

## 2. 契约

`contracts/mobile-api.yaml` 是 Android、iOS 和 Spring Boot 的共同契约。它固定：

- 路径、方法、DTO、枚举和日期格式。
- `code/message/fieldErrors/traceId` 错误结构。
- 移动设备请求头、Bearer Token 和 Token 轮换语义。
- `Idempotency-Key` 与 `If-Match-Version`。
- QQ Ticket、推送注册、更新策略和极简成单状态机。

`scripts/check-mobile-client-contract.py` 检查双端 Endpoint Catalog 是否覆盖全部冻结路径。客户端不得增加 `organizationId`、长期 QQ Token、MCP Token 或任意 URL 代理参数。

## 3. 认证与设备边界

- Access Token 15 分钟；Refresh Token 30 天且每次成功刷新后旧 Token 失效。
- Refresh Token 只保存于 Android Keystore 或 iOS Keychain；服务端仅保存 SHA-256。
- Token 绑定用户、组织、成员、设备、平台和 App 版本。
- 每个 Mobile Bearer 请求必须携带设备、平台、版本名和版本号请求头。
- 密码修改、账号停用、成员失效或设备撤销立即使相关 Token 失效。
- Web Cookie 请求继续执行 CSRF；Mobile Bearer 写请求仅在 Token 验证通过的移动认证路径中豁免 CSRF。

## 4. 极简成单

员工客户详情只有一个“成单”动作。请求体为空，不允许提交说明、金额、证据、主辅名单、业务周期或成交预测。管理员在 Web 治理台确认或驳回，均无文本字段。

客户端只消费服务端派生状态：

- `NONE`：可申请时显示“成单”。
- `PENDING`：显示“待管理员审核”。
- `WON`：主状态显示“已成单”。

确认不会归档客户，也不会改变 A/B/C、意向、负责人、协作者、提醒或时间线。旧业务周期和成交预测只读保留；旧写接口返回 `FEATURE_RETIRED`。

## 5. 离线与隐私

- 离线允许缓存最近客户、时间线、提醒、任务、通知和字典。
- 离线只保存新客户、沟通、每日小结和咨询交接草稿，恢复网络后必须逐条确认。
- 公共池领取、转接、协作者变更、业务状态和任务领取必须在线。
- QQ 原文、AI 完整结果、绩效证据、OAuth/MCP Token 不进入离线数据库、推送或崩溃日志。
- 锁屏推送只显示类型和数量。

## 6. 构建门禁

```bash
bash scripts/verify-mobile-contract.sh contracts/mobile-api.yaml
python3 scripts/check-mobile-client-contract.py
gradle -p android-app --no-daemon testDebugUnitTest lintDebug assembleDebug
swift test --package-path ios-app
```

Android 发布签名和 iOS Ad Hoc 证书、描述文件、设备 UDID 均在仓库外提供。缺少签名材料时，只能交付并证明未签名构建或模拟器构建，不能声称 APK/IPA 已正式签名。

## 7. 发布顺序

1. 在隔离 UAT 数据库执行 Flyway V1 至当前版本。
2. 完成后端、Web、Android、iOS 和合同测试。
3. 双端只连接隔离 UAT 完成登录、客户、时间线、提醒、小结、成单和 QQ 闭环。
4. 备份并恢复验证 PostgreSQL 后，才允许发布正式后端。
5. Android 生成企业签名 APK；iOS 生成 Ad Hoc IPA 并由 Mac 手动安装。

不得执行 `docker compose down -v`，不得删除 PostgreSQL、QQ 或加密原文数据卷。
