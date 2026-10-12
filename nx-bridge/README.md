# NX 2512 进程内桥接

从根目录运行 `scripts/Build-NxBridge.ps1`，使用本机 NX 2512 的 managed 程序集编译到 `.runtime/nx-bridge/NxBridge.dll`。

在 NX 里通过“文件 → 执行 → NX Open”加载 DLL 并勾选允许连接；没有确定按钮。UI Timer 串行调用 NX Open，MCP Server 通过本机原子文件队列传递类型明确的操作。关闭窗口或取消勾选暂停连接。

插件不自动保存零件。新增建模工具需要扩展严格参数验证、snapshot 前置校验、命令回执、Undo 和 NX 2512 回归测试。详细说明见 [接入指南](../docs/MCP_NX.md)。
