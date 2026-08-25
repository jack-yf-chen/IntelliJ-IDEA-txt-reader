# IntelliJ IDEA 小说阅读插件需求与设计

日期：2026-08-24

## 背景

用户正在使用 IntelliJ IDEA 2026.1.3。公开插件市场中已有的小说阅读插件因为版本兼容问题无法正常使用，因此本项目开发一个专门兼容该 IDE 版本的 IntelliJ Platform 插件。

根据 JetBrains 官方兼容性说明，IntelliJ Platform 2026.1 对应构建分支 `261`，运行时要求 Java 21。因此本插件第一版目标平台为 IntelliJ IDEA `2026.1.3`，插件兼容范围设置为 `sinceBuild = "261"`，暂不设置 `untilBuild`，后续如验证需要再收紧兼容范围。

2026-08-24 已查阅的参考资料：

- IntelliJ Platform 构建号范围：https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html
- IntelliJ Platform Gradle Plugin 2.x：https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
- IntelliJ Platform Gradle Plugin 扩展配置：https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html

## 第一版范围

第一版只聚焦 TXT 小说阅读。

必须支持：

- 可安装到 IntelliJ IDEA 2026.1.3 的 IntelliJ Platform 插件。
- 可从 `Tools` 菜单打开插件。
- 提供 `Novel Reader` 工具窗口，默认锚定在右侧。
- 打开本地 `.txt` 文件。
- 尽量正确显示中文 TXT 内容。
- 根据常见 TXT 章节标题识别章节。
- 支持上一章 / 下一章导航。
- 支持从章节选择框跳转章节。
- 支持字号调整。
- 支持行距调整。
- 支持系统字体选择，例如微软雅黑、宋体、仿宋、楷体、黑体等。
- 支持正文文字颜色选择。
- 支持阅读主题选择，例如跟随 IDE、护眼、纸张、暗色。
- 支持正文阅读宽度选择。
- 支持本章进度和全书进度显示。
- 支持基础阅读快捷键。
- 支持隐藏阅读区鼠标光标。
- 支持通过 `Tools -> Novel Reader` 子菜单在文字按钮和简略图标按钮之间切换。
- 支持章节边界连续滚动切换。
- 支持保存并恢复基础阅读状态。

第一版暂不支持：

- EPUB、PDF、MOBI 或在线书源。
- 书架管理。
- 云同步。
- 插件市场发布自动化。
- 复杂主题和排版预设。

## 交互设计

插件主界面是一个名为 `Novel Reader` 的工具窗口。

顶部控件：

- 打开 TXT 文件。
- 上一章。
- 章节选择。
- 章节选择器使用紧凑宽度，只显示可分辨章节序号的短标签，完整章节标题通过鼠标悬浮提示查看。
- 下一章。
- 设置展开 / 收起。

设置区默认隐藏，展开后显示：

- 字号减小。
- 字号增大。
- 行距减小。
- 行距增大。
- 字体选择。
- 文字颜色选择。
- 主题选择。
- 阅读宽度选择。
- 隐藏光标开关。

正文区域：

- 可滚动的纯文本阅读区域。
- 自动换行。
- 支持字号、行距、字体样式调整。
- 支持阅读主题和正文宽度调整。
- 支持正文文字颜色调整。
- 默认隐藏高级阅读设置，减少工具栏占用的垂直空间。
- 滚动到章节末尾后继续向下滚动，自动进入下一章。
- 滚动到章节开头后继续向上滚动，自动进入上一章末尾。
- 支持 `Space`、`PageUp`、`PageDown`、`Alt + 左右方向键`、`Ctrl + =`、`Ctrl + -` 等快捷键。
- 支持选中文字后右键进行汉典查词、DeepL 翻译、浏览器搜索和复制，减少离开阅读器手动搜索的操作。

状态区域：

- 当前文件名。
- 当前章节序号和总章节数。
- 本章进度。
- 全书进度。
- 当前编码。

## 技术设计

语言和构建：

- Kotlin
- Gradle Kotlin DSL
- IntelliJ Platform Gradle Plugin 2.x
- Java toolchain 21

主要组件：

- `OpenNovelAction`：注册到 `Tools -> Novel Reader` 子菜单，打开 TXT 文件选择器并激活工具窗口。
- `ToggleButtonStyleAction`：注册到 `Tools -> Novel Reader` 子菜单，在文字按钮和简略图标按钮之间切换阅读工具栏显示方式。
- `NovelReaderToolWindowFactory`：创建工具窗口并安装阅读面板。
- `ReaderPanel`：基于 Swing 的阅读 UI，负责文件打开、章节导航、阅读样式控制和状态保存。
- `TxtBookLoader`：TXT 文件读取器，支持编码回退。
- `ChapterParser`：基于常见中英文章节标题的简单章节解析器。
- `ReaderStateService`：项目级持久化状态，保存最后打开文件、编码、章节索引、滚动位置、字体、文字颜色、字号、行距、主题、阅读宽度、隐藏光标开关和按钮显示方式。

划词查找策略：

- 在阅读正文组件上挂载右键菜单。
- 菜单显示前读取当前选中文本，没有选中文本时禁用查词、翻译、搜索和复制入口。
- `汉典查词` 打开 `https://www.zdic.net/hans/` 对应词条，适合中文生僻字词、成语和古文词汇。
- `DeepL 翻译` 打开 DeepL 翻译页，目标语言默认简体中文，适合外文短句或片段。
- `浏览器搜索` 使用搜索引擎作为兜底，适合汉典未覆盖的专名或长句。
- 所有外部跳转都通过 IntelliJ Platform 的浏览器工具打开，不在插件内直接调用未公开接口。
- 词典查找默认截取前 80 个字符，翻译和搜索默认截取前 500 个字符，避免超长选区生成异常 URL。

TXT 编码策略：

1. 尝试 UTF-8。
2. 尝试 GB18030。
3. 尝试 GBK。
4. 全部失败时提示可读错误。

章节解析策略：

- 匹配常见章节行，例如：
  - `第1章`
  - `第一章`
  - `第001回`
  - `Chapter 1`
- 如果没有识别到章节，将全文作为单章处理。

## 验证计划

最低本地验证：

- 编译 Kotlin 源码。
- 运行 Gradle 插件结构校验。
- 使用 `buildPlugin` 构建插件 ZIP。

在 IntelliJ IDEA 2026.1.3 中手动验证：

- 安装生成的 ZIP 插件。
- 确认 `Tools -> Novel Reader` 可见。
- 打开 UTF-8 TXT 文件。
- 打开 GBK 或 GB18030 中文 TXT 文件。
- 确认文本显示、章节识别、章节跳转、连续滚动切章、字号调整、行距调整、字体选择和状态恢复正常。
- 确认文字颜色、主题、阅读宽度、进度显示、快捷键、隐藏光标开关和按钮显示方式切换正常。
- 确认选中文本后右键菜单可用，汉典查词、DeepL 翻译、浏览器搜索和复制行为正常。

## 开发日志

- 2026-08-24：实现前创建需求与设计文档。
- 2026-08-24：创建 Kotlin、Gradle Kotlin DSL、IntelliJ Platform Gradle Plugin `2.18.1`、IntelliJ IDEA `2026.1.3` 目标平台的项目骨架。
- 2026-08-24：实现 TXT 读取，支持 UTF-8、GB18030、GBK 编码回退。
- 2026-08-24：实现常见中文章节标题和 `Chapter N` 标题的简单章节解析。
- 2026-08-24：实现 `Tools -> Novel Reader` 菜单入口和右侧 `Novel Reader` 工具窗口。
- 2026-08-24：实现阅读面板，支持文件打开、章节选择、上一章 / 下一章、字号控制和项目级阅读状态持久化。
- 2026-08-24：最初因本机命令行没有安装或未配置 `gradle` 和 `kotlinc`，未能完成本地构建；详细验证记录见 `docs/development-record.md`。
- 2026-08-24：明确约定后续开发过程中新增的注释和文档使用中文。
- 2026-08-25：创建 `codex/dictionary-lookup` 分支，将插件版本号提升到 `0.2.0`，增加划词右键查词和翻译能力。
