# Novel Reader

Novel Reader 是一个轻量级 IntelliJ Platform 插件，用于在 IntelliJ IDEA 内阅读本地 TXT 小说。

第一版目标环境：

- IntelliJ IDEA 2026.1.3
- IntelliJ Platform 构建分支 261

主要功能：

- `Tools -> Novel Reader` 菜单入口。
- `Novel Reader` 工具窗口。
- TXT 文件读取，支持 UTF-8、GB18030、GBK 编码回退。
- 基础章节识别。
- 上一章 / 下一章导航。
- 章节边界连续滚动切换。
- 字号、行距、字体样式调整。
- 阅读状态持久化。

构建：

```powershell
gradle buildPlugin
```

生成的插件 ZIP 位于：

```text
build/distributions/
```

如果 `gradle` 不在 `PATH` 中，可以安装 Gradle，或用 IntelliJ IDEA 将本目录作为 Gradle 项目打开，然后在 Gradle 工具窗口中运行 `buildPlugin` 任务。

