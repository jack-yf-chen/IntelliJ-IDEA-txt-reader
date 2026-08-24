# 开发记录

日期：2026-08-24

## 开发约定

- 后续开发过程中新增的注释和文档统一使用中文。
- 技术名词、类名、方法名、命令、插件 ID 和外部链接可以保留英文原文。

## 已实现内容

- 创建 IntelliJ Platform 插件项目骨架。
- 增加 Gradle Kotlin DSL 配置，目标 IDE 为 IntelliJ IDEA `2026.1.3`。
- 设置 Java/Kotlin toolchain 为 Java 21。
- 设置插件兼容分支为 IntelliJ Platform `261`。
- 在 `META-INF/plugin.xml` 中增加插件元信息和 IntelliJ 注册项。
- 增加 `Tools -> Novel Reader` 菜单入口。
- 增加右侧 `Novel Reader` 工具窗口。
- 增加 TXT 读取器，支持 UTF-8、GB18030、GBK 编码回退。
- 增加章节解析器，支持常见中文章节标题和 `Chapter N`。
- 增加 Swing 阅读面板，包含：
  - 打开 TXT
  - 上一章
  - 章节选择
  - 下一章
  - 字号减小 / 增大
  - 行距减小 / 增大
  - 字体选择
  - 文字颜色选择
  - 主题选择
  - 阅读宽度选择
  - 隐藏光标开关
  - 状态显示
- 增加项目级阅读状态持久化：
  - 最后打开文件路径
  - 编码
  - 当前章节索引
  - 滚动位置
  - 字体
  - 文字颜色
  - 字号
  - 行距
  - 主题
  - 阅读宽度
  - 隐藏光标开关
  - 按钮显示方式
- 增加 `.gitignore` 和项目 `README.md`。

## 新增文件

- `.gitignore`
- `README.md`
- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties`
- `docs/requirements-and-design.md`
- `docs/development-record.md`
- `src/main/resources/META-INF/plugin.xml`
- `src/main/resources/icons/reader.svg`
- `src/main/kotlin/com/chen/reader/ChapterParser.kt`
- `src/main/kotlin/com/chen/reader/NovelReaderOpener.kt`
- `src/main/kotlin/com/chen/reader/NovelReaderToolWindowFactory.kt`
- `src/main/kotlin/com/chen/reader/OpenNovelAction.kt`
- `src/main/kotlin/com/chen/reader/ReaderPanel.kt`
- `src/main/kotlin/com/chen/reader/ReaderStateService.kt`
- `src/main/kotlin/com/chen/reader/TxtBookLoader.kt`
- `src/main/kotlin/com/chen/reader/model/Book.kt`

## 已执行验证

- 编辑前检查仓库状态：仓库除 `.git` 外为空。
- 使用 `rg --files` 检查生成文件清单。
- 使用 `rg` 检查是否存在遗留标记或旧配置。
- 检查 `plugin.xml` 中是否包含预期的 Action 和 Tool Window 注册。
- 检查本机构建工具可用性：
  - `gradle --version` 失败，原因是 `gradle` 未安装或不在 `PATH` 中。
  - `kotlinc -version` 失败，原因是 `kotlinc` 未安装或不在 `PATH` 中。
  - `GRADLE_HOME` 未设置。
  - 初始检查时，当前用户 Gradle 缓存中没有可复用的 IntelliJ Platform Gradle Plugin 缓存。
- 使用 IntelliJ IDEA 2026.1.3 自带 JBR 运行 `buildPlugin`，构建成功。

## 初始未完成验证

- 初始阶段未能运行 Gradle 编译。
- 初始阶段未能运行 `buildPlugin`。
- 初始阶段未生成插件 ZIP。
- 初始阶段未在 IntelliJ IDEA 2026.1.3 中手动安装验证。

## 修复和迭代记录

- 2026-08-24：修复 Gradle settings 配置。项目导入时报错 `settings.gradle.kts` 中 `intellijPlatform` 和 `defaultRepositories` 无法解析，已增加官方 `org.jetbrains.intellij.platform.settings` 插件，并将 IntelliJ Platform 插件版本声明移动到 settings 中。
- 2026-08-24：移除过期的 IntelliJ Platform 依赖辅助函数。Gradle 报错无法解析 `instrumentationTools()`，IntelliJ Platform Gradle Plugin 2.18.1 不再需要该函数处理代码插桩。
- 2026-08-24：修复 Kotlin 编译失败。目标 IntelliJ Platform 中没有 `UIUtil.getTextAreaBackground()`，改为使用 Swing `UIManager.getColor("TextArea.background")`。
- 2026-08-24：使用 IDEA 2026.1.3 自带 JBR 作为 Gradle 运行时验证 `buildPlugin`，构建成功，并在 `build/distributions/` 下生成插件 ZIP。
- 2026-08-24：成功启动 `runIde`。沙箱 IntelliJ IDEA 2026.1.3 Welcome 窗口已打开，打开项目时到达信任项目弹窗，并在信任前取消 Microsoft Defender 排除列表勾选。后续 UI 验证因用户按下 Escape 停止 Computer Use 而中止。
- 2026-08-24：修复 `Novel Reader` 工具窗口变窄时顶部工具栏被裁剪的问题。操作按钮保留在第一行，章节选择器独占第二行，避免章节选择器和后续按钮被隐藏。
- 2026-08-24：增加阅读舒适度控制。阅读区改用 `JTextPane` 和段落样式，保留字号控制，增加行距控制，持久化行距，并在状态栏显示当前字号和行距。
- 2026-08-24：增加章节边界连续滚动和字体选择。章节末尾继续向下滚动时进入下一章，章节开头继续向上滚动时进入上一章末尾。阅读器列出已安装系统字体，并优先展示常见中文字体，选择后立即应用并持久化。
- 2026-08-24：增加阅读主题、阅读宽度、进度显示、阅读快捷键和隐藏光标开关。状态栏显示本章进度和全书进度；阅读区支持主题色、最大阅读宽度模拟、翻页/章节/字号/行距快捷键；隐藏光标开关会在阅读区内使用透明鼠标光标，并保持文本选区可见。
- 2026-08-24：压缩阅读窗口工具栏高度。默认只显示打开、上一章、章节选择、下一章和设置按钮；字号、行距、字体、主题、宽度和隐藏光标等高级设置移动到可展开设置区，默认隐藏。
- 2026-08-24：精简底部状态栏，移除字体、字号、行距等设置类信息，仅保留文件名、章节、本章进度、全书进度和编码。
- 2026-08-24：增加正文文字颜色选择和按钮显示方式切换。设置区新增文字颜色下拉框；工具栏按钮支持文字模式和简略图标模式；`Tools -> Novel Reader` 子菜单新增按钮格式切换入口；图标按钮均设置鼠标悬浮提示。

## 后续验证步骤

运行：

```powershell
gradle buildPlugin
```

或使用项目自带 Gradle Wrapper：

```powershell
.\gradlew.bat buildPlugin
```

生成的插件 ZIP 位于：

```text
build/distributions/
```

手动冒烟验证：

- 确认 `Tools -> Novel Reader` 可见。
- 打开 UTF-8 TXT 文件。
- 打开 GBK 或 GB18030 中文 TXT 文件。
- 确认章节检测和章节导航正常。
- 确认章节边界连续滚动切换正常。
- 确认字号、行距、字体选择正常。
- 确认文字颜色、阅读主题、阅读宽度、进度显示、快捷键、隐藏光标开关和按钮显示方式切换正常。
- 重启 IntelliJ IDEA 后确认阅读状态可以恢复。
