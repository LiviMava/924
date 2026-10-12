# NX MCP Server

Spring Boot / Spring AI MCP WebMVC Server，Streamable HTTP `/mcp`，默认仅监听 `127.0.0.1:8765`。

从仓库根目录运行 `scripts/Start-NxMcp.ps1`；配置、NX 2512 插件加载、协议、确认与恢复流程见 [接入指南](../docs/MCP_NX.md)。`-Mock` 仅用于模拟测试，真实后端要求在 NX 里加载桥接 DLL。

主模块 `java-chatbot` 是 MCP Host/Client，模型密钥仅由主模块使用。此服务使用独立 `NX_MCP_API_KEY`；不读取 DeepSeek 密钥调用模型。
