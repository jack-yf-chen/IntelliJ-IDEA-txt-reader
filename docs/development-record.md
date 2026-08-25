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
- 2026-08-24：优化章节识别规则，避免正文中类似“第四回中...”的叙述句被误识别为章节标题。章节候选行现在会过滤过长标题、明显句中标点，以及章回标记后紧跟叙述连接词的行。
- 2026-08-24：压缩章节下拉框宽度。章节选择器不再占满工具栏横向空间，默认只显示 `第57回`、`Chapter 1` 这类短章节标识；完整章节标题保留在鼠标悬浮提示中。
- 2026-08-24：修复隐藏光标模式下的键盘阅读体验。阅读正文改用不可见文本光标，避免点击正文后竖线光标再次出现；方向键绑定到阅读面板，勾选隐藏光标或调整设置后会自动把焦点交还阅读区，上下键可直接滚动并在章节边界连续切章。
- 2026-08-24：修复正文获取焦点后方向键仍优先移动隐藏文本光标的问题。阅读快捷键现在同时覆盖正文组件的聚焦输入映射，方向键会直接滚动阅读区，不再等待不可见光标移动到可视边界。
- 2026-08-25：创建 `codex/dictionary-lookup` 功能分支，并将插件版本号提升到 `0.2.0`。
- 2026-08-25：增加阅读区划词右键菜单。选中文字后可直接进行汉典查词、DeepL 翻译、浏览器搜索和复制；没有选中文本时相关菜单项自动禁用。外部查找通过 IntelliJ Platform 浏览器工具打开，避免依赖未公开翻译接口。
- 2026-08-25：根据新的查词需求调整划词菜单。去掉 DeepL 翻译入口，将浏览器搜索改为百度搜索；加入 `chinese-xinhua` 的 `word.json` 和 `ci.json` 作为本地词典资源，并保留 MIT License 文件。
- 2026-08-25：将插件版本号提升到 `0.2.1`。本地词典查找采用后台任务和首次懒加载，查到时弹窗显示词条，查不到时弹窗提示未找到。
- 2026-08-25：将插件版本号提升到 `0.2.2`。阅读区从单章替换渲染改为整本书连续渲染，章节选择、上一章和下一章改为滚动定位；滚动时按可视文本偏移自动更新当前章节和进度，解决章节切换生硬、无法同时查看章节尾部与下一章开头的问题。
- 2026-08-25：将插件版本号提升到 `0.2.3`。阅读记录从滚动条像素值升级为全书字符偏移、原文锚点和章节内千分比组合；恢复时优先按锚点校验定位，锚点失效时按章节进度兜底，旧 `scrollValue` 保留为兼容字段。
- 2026-08-25：将插件版本号提升到 `0.2.4`。阅读区从整本书连续渲染改为章节窗口化渲染，每次只渲染当前章节全文和上下文预览；章节状态、阅读进度和阅读记录统一按视口 25% 高度处的阅读锚点计算，窗口大小或阅读样式变化后延迟按锚点重新定位，减少重排卡顿和定位漂移。
- 2026-08-25：将插件版本号提升到 `0.2.5`。阅读正文右侧增加额外选择缓冲边距，避免行尾最后一个字靠近滚动条或边框时没有足够鼠标落点，导致无法选中后查词。

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
- 确认章节边界可以连续阅读，章节尾部和下一章开头可以同屏查看。
- 确认拖动工具窗口大小、修改字号、行距或阅读宽度后，当前位置不会明显漂移。
- 确认正文每行最后一个字可以正常拖选和右键查词。
- 确认字号、行距、字体选择正常。
- 确认文字颜色、阅读主题、阅读宽度、进度显示、快捷键、隐藏光标开关和按钮显示方式切换正常。
- 确认阅读区选中文本后右键菜单可用，本地词典查找、汉典查词、百度搜索和复制正常。
- 重启 IntelliJ IDEA 后确认阅读状态可以恢复；调整窗口大小、字号、行距或阅读宽度后，恢复位置仍应接近上次阅读文字。
