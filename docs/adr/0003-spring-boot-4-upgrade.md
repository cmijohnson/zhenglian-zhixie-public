# ADR 0003: 跟踪 Spring Boot 4 升级

- 状态：已接受
- 日期：2026-07-19

## 背景

项目需求指定 Spring Boot 3.x，因此 MVP 锁定 3.5.16。该版本是 3.5.x 的最后一个开源发布版本。

## 决策

建立自动化依赖扫描，不在 3.x 上继续增加与框架深度绑定的组件。上线后单独建立 Boot 4 升级分支，先验证 Spring Security、Session JDBC、springdoc、Flyway 和 Testcontainers，通过完整集成测试后才切换。

## 验收条件

所有 Flyway 从零迁移、组织隔离、Session/CSRF、导入迁移及浏览器主流程测试在 Boot 4 上全部通过。
