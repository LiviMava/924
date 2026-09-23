# 企业文档 RAG 服务

本模块在原 Java Chatbot 基础上扩展为 Spring Boot + Spring AI 文档问答服务。

请从仓库根目录按 [启动指南](../README.md) 运行 `scripts/Start-Local.ps1`，以确保数据库、文件、日志、Maven 缓存和模型配置使用同一套项目内路径。

- [架构与扩展点](../docs/ARCHITECTURE.md)
- [API 合同](../docs/API.md)
- [运维与部署](../docs/OPERATIONS.md)
- [验证记录](../docs/VALIDATION.md)

业务 API 为 `/api/v1`，网页与 API 均由本模块提供。旧自由聊天端点默认关闭。
