# Novel Reader

Novel Reader 是一个用于 IntelliJ IDEA 的轻量级本地小说阅读插件，目标是在不离开 IDE 的情况下完成本地小说阅读、章节导航、阅读样式调整和划词查词。

当前版本：`0.6.0`

## 目标环境

- IntelliJ IDEA 2026.1.3
- IntelliJ Platform 构建分支 `261`
- JDK / JetBrains Runtime 21

## 主要功能

- `Tools -> Novel Reader` 菜单入口。
- 右侧 `Novel Reader` 工具窗口，包含“本地阅读”和“Neat Reader”两个 Tab。
- 内嵌 Neat Reader Web 端，默认打开 `https://www.neat-reader.cn/webapp`，并提供官网和外部浏览器打开入口。
- Neat Reader 内嵌页按离散档位自动缩放：插件先由窗口宽度反推「站点看到的 CSS 视口宽度」，再选档，并带迟滞带，临界附近不会反复抖动；目标优先保证"不错版"（不出现宽屏窄列版式）。
- Neat Reader 手动缩放会被记住并锁定，点「跟随窗口」可解锁回到自动换档。
- Neat Reader 新窗口拦截脚本只在页面加载完成后注入，并带校验与重试，降低空白弹窗概率。
- TXT 文件读取，支持 UTF-8、GB18030、GBK 编码回退。
- EPUB 文件读取，支持按 OPF spine 顺序提取 XHTML 正文并生成章节导航。
- EPUB 脚注和基础复杂排版还原：脚注引用、章节末尾注释、标题、列表、引用、表格、图片 alt、ruby 注音和强调文本会转成更适合纯文本阅读的结构。
- EPUB 图片显示：正文里的 `<img>` 会渲染成图片块，排版高度只取文件头探测到的内建宽高（**不解码**），
  位图在后台线程解码后再 repaint，不会引起正文重排。SVG 等拿不到内建尺寸的按固定占位框渲染。
- EPUB 脚注弹窗：正文里的脚注引用（如 `[注1]`）可点击，弹窗直接显示注释正文，不做跳转；
  弹窗标题保留书里原本的标记（如 `[1]`），便于和插件编号对不上时排查。
- 脚注引用内联排版：正文里的脚注引用 `[注N]` **跟前后正文排在同一行**（不再被强制换行，也不再多出空行）。
  XHTML 源码里的缩进换行是给人看源码用的、不是语义换行，数据层先把它们折叠成空格，
  渲染层再把连续的可内联块并成一段折行；段落分隔仍由块内容自带的换行表达，不会因此粘连。
- 脚注标记着色：正文里的 `[注N]` 与章末注释条目开头的 `[注N]` 用**区别于正文的颜色并加下划线**显示，
  一眼能看出哪些字是注解入口。颜色跟随阅读主题换算（浅底深蓝、深底亮蓝），并按 WCAG 对比度兜底，
  保证在任何主题下都清晰可辨。只有含脚注的行才走分段绘制，纯正文行的绘制开销不变。
- 图片灯箱：点击正文图片可放大查看，支持滚轮缩放、拖拽平移、1:1、适配窗口，Esc 关闭。
- 热区手型光标：鼠标移到脚注引用或图片上会变成手型。开了「隐藏光标」时手型光标仍然优先显示，
  否则隐藏光标会让注解和图片点不到。
- 常见中文章、节、回、卷、集、部、篇，以及 `Chapter N` 章节识别。
- 上一章 / 下一章 / 章节下拉框导航。
- 全书虚拟阅读渲染：滚动条对应整本书连续高度，只绘制当前可见行，章节边界不再替换正文内容。
- 窗口缩放时延迟重排并按可视锚点恢复，减少缩放过程中的卡顿和章节漂移。
- 阅读记录持久化：保存全书字符偏移、原文锚点和章节内进度，降低窗口大小、字号、行距变化对恢复位置的影响。
- 重新打开同一本 TXT/EPUB 或重启 IDE 后，会优先按上次阅读锚点恢复，避免从头开始。
- 字号、行距、字体、多档字重、文字颜色、主题和阅读宽度调整。
- 本章进度和全书进度显示。
- 阅读快捷键。
- 隐藏鼠标光标。
- 紧凑工具栏，高级设置默认隐藏。
- 文字按钮 / 简略图标按钮两种工具栏显示方式。
- 选中文本后右键进行本地词典查找、汉典查词、百度搜索和复制。
- 虚拟阅读组件按行保存字符位置，提升行尾选字和跨行拖选稳定性。
- 阅读区右侧选字缓冲，方便选中行尾最后一个字。

## Neat Reader 缩放调参

内嵌网页的自动缩放以「站点看到的 CSS 视口宽度」为目标（CEF 语义：`cssWidth = 窗口像素宽 / zoomFactor`），目标区间写在
`NeatReaderPanel.kt` 的 `TARGET_CSS_WIDTH_MIN` / `TARGET_CSS_WIDTH_MAX` 两个常量里。站点真实断点需要真机测量，测量方法：

1. `Help -> Show Log in ...` 打开 IDE 日志，搜索 `Neat Reader 缩放`。
2. 缓慢拖动工具窗口宽度，日志会逐档打印：

   ```text
   Neat Reader 缩放[resize 防抖] viewportWidth=1180 zoomLevel=0.80 zoomFactor=1.157 cssWidth=1020 档位=2 显示=115% 目标区间=[600,1024] 迟滞=40 locked=false
   Neat Reader 页面回报: metrics innerWidth=1020 clientWidth=1020 bodyWidth=1020
   ```

3. 找到**第一次出现「双列 / 窄正文 + 侧边栏」版式**的那一行，把它当时的 `cssWidth`（或 `innerWidth`）记下来，
   把 `TARGET_CSS_WIDTH_MAX` 改成比它小 40~80 的值。
4. 反之，若窄窗口下正文被挤压或出现横向滚动，把那一刻的 `cssWidth` 记下来，把 `TARGET_CSS_WIDTH_MIN` 改成比它大 40~80 的值。
5. 档位切换太迟钝或太敏感时调 `HYSTERESIS_CSS_PX`；相邻档肉眼看不出区别时把 `ZOOM_LEVEL_STEP` 调大。

改完重新编译即可，不需要动其它逻辑。手动「缩小 / 放大」会被持久化并锁定自动换档，点「跟随窗口」解锁。

### 排查：宽窗口下「保不错版」失效

缩放档位受 `MIN_ZOOM_LEVEL` / `MAX_ZOOM_LEVEL`（默认 `-3.0 ~ 3.0`，对应倍率 `0.578 ~ 1.728`）限制。
如果窗口太宽，需要的放大倍率超过上限，`cssWidth` 就压不回 `TARGET_CSS_WIDTH_MAX`，宽屏版式仍会出现。
这时日志里会有明确的 WARN：

```text
Neat Reader 缩放已达上限，cssWidth 压不回目标区间：viewportWidth=2000 zoomLevel=3.00 zoomFactor=1.728 cssWidth=1157 目标区间=[600,1024] ...
```

**排查顺序**：先搜日志里有没有 `缩放已达上限` / `缩放已达下限`。

- **有** → 根因是缩放上下限被夹住，**改 `TARGET_CSS_WIDTH_MAX` 是无效的**，应放宽 `MAX_ZOOM_LEVEL`（窄窗口则放宽 `MIN_ZOOM_LEVEL`）。
- **没有** → 才是断点猜错了，按上面第 3 步调 `TARGET_CSS_WIDTH_MAX`。

## 划词查词

阅读区选中文字后右键可使用：

- `本地词典查找`：在插件内弹窗显示本地词典结果。
- `汉典查词`：打开汉典网页进行外部查词。
- `百度搜索`：打开百度搜索作为兜底。
- `复制`：复制选中文本。

本地词典数据来自 [pwxcoo/chinese-xinhua](https://github.com/pwxcoo/chinese-xinhua)，当前随插件打包的资源包括：

- `src/main/resources/dictionary/word.json`
- `src/main/resources/dictionary/ci.json`
- `src/main/resources/dictionary/chinese-xinhua-LICENSE`

`chinese-xinhua` 使用 MIT License，版权信息为 `Copyright (c) 2018 PWXCOO`。本项目保留了其许可证文件。该词典数据由原项目整理自网络公开资料，若用于公开分发或上架插件市场，请自行评估数据来源和版权风险。

## 构建

推荐使用项目自带 Gradle Wrapper：

```powershell
.\gradlew.bat buildPlugin
```

如果已经安装 Gradle，也可以运行：

```powershell
gradle buildPlugin
```

生成的插件 ZIP 位于：

```text
build/distributions/
```

如果在 Windows 上使用 IntelliJ IDEA 自带 JBR 构建，可以参考：

```powershell
$env:JAVA_HOME='D:\software\IntelliJ IDEA 2026.1.3\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat buildPlugin --no-daemon
```

## 本地开发

1. 使用 IntelliJ IDEA 打开项目根目录。
2. 等待 Gradle 同步完成。
3. 在 Gradle 工具窗口运行 `buildPlugin` 生成插件包。
4. 在 IntelliJ IDEA 中通过 `Settings -> Plugins -> Install Plugin from Disk...` 安装 `build/distributions/` 下的 ZIP。

## 说明

- 插件目前优先面向 TXT 和 EPUB 小说阅读，暂不支持 PDF、MOBI 或在线书源。
- 本地词典采用首次查词时懒加载，首次查询可能略慢。
- 词典 JSON 数据会显著增加插件包体积，当前版本选择随包内置，后续可考虑改为可选下载数据包。
