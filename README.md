# Novel Reader

Novel Reader 是一个用于 IntelliJ IDEA 的轻量级 TXT 小说阅读插件，目标是在不离开 IDE 的情况下完成本地小说阅读、章节导航、阅读样式调整和划词查词。

当前版本：`0.3.0-alpha.1`

## 目标环境

- IntelliJ IDEA 2026.1.3
- IntelliJ Platform 构建分支 `261`
- JDK / JetBrains Runtime 21

## 主要功能

- `Tools -> Novel Reader` 菜单入口。
- 右侧 `Novel Reader` 工具窗口。
- TXT 文件读取，支持 UTF-8、GB18030、GBK 编码回退。
- 常见中文章、节、回、卷、集、部、篇，以及 `Chapter N` 章节识别。
- 上一章 / 下一章 / 章节下拉框导航。
- 全书虚拟阅读渲染：滚动条对应整本书连续高度，只绘制当前可见行，章节边界不再替换正文内容。
- 阅读记录持久化：保存全书字符偏移、原文锚点和章节内进度，降低窗口大小、字号、行距变化对恢复位置的影响。
- 字号、行距、字体、文字颜色、主题和阅读宽度调整。
- 本章进度和全书进度显示。
- 阅读快捷键。
- 隐藏鼠标光标。
- 紧凑工具栏，高级设置默认隐藏。
- 文字按钮 / 简略图标按钮两种工具栏显示方式。
- 选中文本后右键进行本地词典查找、汉典查词、百度搜索和复制。
- 阅读区右侧选字缓冲，方便选中行尾最后一个字。

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

- 插件目前优先面向 TXT 小说阅读，暂不支持 EPUB、PDF 等格式。
- 本地词典采用首次查词时懒加载，首次查询可能略慢。
- 词典 JSON 数据会显著增加插件包体积，当前 `0.3.x` 版本选择随包内置，后续可考虑改为可选下载数据包。
