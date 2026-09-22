# 0.9.0 章节识别增强（增量设计）

> 本文由架构角色产出，针对 team-lead 反馈的「章节识别支持太弱」三个症状（粒度错、常见格式不认、标题显示错），
> 给出 EPUB 目录驱动 + TXT 规则表扩展两套方案的**可直接编码**设计。
> 全部结论基于 team-lead 提供的三本**实测**数据，不使用估算值。

---

## 1. 实测数据与由此推出的三条硬事实

> **数据版本说明**：下表为 team-lead 用 `dc:title` + md5 **复核后的权威数据**。
> 早期一版把 b2 / b3 的身份测反了（"120 回小说"那本书并不存在），本表已纠正。

| 文件 | `dc:title`（实测） | spine | 目录来源 | 各层条目数 | 现状章节数 |
|---|---|---|---|---|---|
| **b1.epub** | 作为意志和表象的世界（汉译世界学术名著丛书）/ 叔本华 | 12 | **NCX 82 条**，**无 nav.xhtml** | L1=11 / **L2=71** | 12 |
| **b2.epub** | 深入理解 AI Agent：设计原理与工程实践 / 李博杰 | 15 | NCX 286 条 **+** nav.xhtml 290 链接 | ncx L1=13 / **L2=92** / L3=181<br>nav L1=14 / **L2=92** / L3=181 | 14 |
| **b3.epub** | 叔本华：意志和表象的世界 | 15 | **NCX 仅 14 条**，**无 nav.xhtml** | **L1=14** | 14 |

b3 的 14 条 NCX 是**前页**性质：封二 / 书名页 / 版权页 / 目录 / 导读 …

由此得到三条不可回避的事实：

1. **NCX 与 nav 两种目录都必须支持**（不是"nav 优先能救某本书"）：
   b1 和 b3 **只有 `toc.ncx`、没有 `nav.xhtml`**，只认 nav 的实现在这两本上会直接退化；
   b2 两者都有（nav 290 链接 vs ncx 286 条，nav 略全），此时以 **nav 为准**。
   且现状 `readManifest` 把 ncx 落进 `resources` 分支（因为 `isReadableDocument` 对
   `application/x-dtbncx+xml` 返回 false），**从未被解析过**——这是必须先补的洞。
2. **必须支持 fragment 定位**：b1 用 12 个文档装 71 章（82 条 NCX 全部带 fragment），
   b2 用 15 个文档装 92 章（286 条中 285 条带 fragment）。
   只按"一个 spine 文档 = 一章"切分，b1 会少 59 章、b2 会少 78 章。
   **"同文档多章节"是主场景，不是边缘场景。**
   （b3 的 14 条前页条目多条落在同一文档内，同样依赖 fragment；具体分布待实测确认。）
3. **现有加载链路已具备落点能力**：`\u0000<kind>:<value>\u0000` 哨兵机制（`MARK`、`splitMarkedBody`、`ChapterWriter`）
   已经跑通了图片和脚注两类零宽/短标记，TOC 锚点是同构问题，不需要新机制。

> **附注**：b3 章节数不会变（14 → 14），它的价值在**标题**——
> 现状前三章标题都是重复的「叔本华：意志和表象的世界」，改用 NCX 后才是 封二 / 书名页 / 版权页 …。

---

## 2. 方案总览

```
EPUB:  spine 顺序写盘（不变）
         ├─ 读 nav.xhtml → 失败则读 toc.ncx → 都失败则退化为「一篇 spine 一章」
         ├─ 决策 A：按 [8,120] 规则自动选最深合格层
         ├─ 决策 B：把选中层每条 TOC 的 (docPath, fragment) 变成 plainText 里的一个 offset
         └─ 决策 C：按 offset 升序闭包成 Chapter 列表

TXT:   决策 D：ChapterParser 规则表从 1 条扩到 7 条，并新增 5 条守卫
```

**红线（team-lead 明确要求）**：`Book.plainText` **逐字符不得改变**。
0.6.0 已经因为改了纯文本口径导致过一次进度漂移，本版不允许重演。

---

## 3. 决策 A：层级自动选择规则

### A.1 规则

把 TOC 按 `depth`（L1=1, L2=2, …）分组，统计每层条目数 `n(L)`：

```
候选层 = { L | n(L) ∈ [8, 120] }
选中   = 候选层中 depth 最大者 L*
退化1  = 候选层为空 → 取 n ≤ 120 中 n 最大者（即最接近上界的那层）
退化2  = 所有层 n > 120 → 取最浅层（n 最小的那层）
```

### A.2 用实测数据验算

| 书 | 目录来源 | 各层 n | 候选层 | L* | 章节数 |
|----|---|---|---|---|---|
| **b1**（叔本华） | 仅 NCX 82 条 | L1=11, L2=71 | L1 ✓(11∈[8,120])、L2 ✓(71∈[8,120]) | **L2** | **71** |
| **b2**（AI Agent） | nav + NCX（以 nav 为准） | L1=14, L2=92, L3=181 | L1 ✓、L2 ✓、L3 ✗(181>120) | **L2** | **92** |
| **b3**（叔本华·另一版） | 仅 NCX 14 条 | L1=14 | L1 ✓(14∈[8,120]) | **L1** | **14** |

**结论：team-lead 提出的 `[8,120]` 取最深合格层规则在新数据上依然成立，三本书全部落在合理粒度上。**

上下界的语义：
- **下界 8**：某层只有个位数条目时，它通常是"前言/附录/索引"这类零散条目，回退到上一层更合理。
- **上界 120**：b2 的 L3=181 意味着 92 章被再切成 181 段——那是**节/小节**粒度，作为目录项过碎，必须挡掉。
  （注：b2 的 181 与 92 都远大于其 spine 数 15，说明这本书的目录层级远细于文档切分，
  上界在这里起的是"挡掉小节层"的作用，而不是"对齐 spine 数"。）

> b3 的收益不在章节数（14 → 14）而在**标题**：现状前三章标题重复为「叔本华：意志和表象的世界」，
> 改走 NCX 后是 封二 / 书名页 / 版权页 / 目录 / 导读 …。

### A.3 父标题补全（**要做**）

b1 选中 L2 后，71 个标题是裸的「§1」「§2」——直接显示在目录里完全没有可读性。

**补全规则**：

```
触发条件：选中的条目标题里不含任何章节关键词
          第 / 章 / 节 / 回 / 卷 / 篇 / 部 / 集 / chapter（忽略大小写）/ 阿拉伯数字或中文数字序号
输出格式：「{最近的有效祖先标题} · {本条标题}」
跳过：   祖先缺失、祖先标题与子标题相同、拼接后长度 > 80
```

b1 的效果：「§1」→「第一章 · §1」。
b2、b3 的 L2 标题本身带序号或文字，不触发补全。

> 补全过程只影响 `Chapter.title`（展示层），不触碰 `plainText`，因此**不影响位置恢复**。

---

## 4. 决策 B：fragment → offset 的落点方案（哨兵法）

### B.1 为什么不用"事后匹配标题文本"

`plainText` 里搜 TOC 标题字符串来反推 offset，是最直觉的做法，但有三个致命伤：
① 正文里出现同名文字会误命中；② TOC 标题常带 HTML 实体/空白差异，匹配不上；
③ 一旦匹配失败就整章偏移。**放弃这条路。**

### B.2 哨兵法（采纳）

复用现有 `\u0000<kind>:<value>\u0000` 机制，新增一种 `kind = "TOC"`：

**步骤 1 —— 插入（在 `buildChapterBody` 内，最前）**

```
对每个 TOC 条目 e（e.docPath == 当前 spine 文档，且 e.fragment 非空）：
    pos = findElementByIdTag(body, e.fragment)?.first      // 现有函数，:383
          ?: continue                                       // 定位失败 → 该条目丢弃，交给决策 C 兜底
    记录 (pos → e.index)
从后往前插入 "\u0000TOC:{e.index}\u0000" 到 pos 之后（即元素内容之前）
```

三个关键点：
- **插入时机必须在最前**（`buildChapterBody:256` `val isToc` 之后、通道 A/B 之前）。
  这样 `removeRanges`、`replaceFootnoteRefs`、`replaceImages` 的所有区间计算都在**同一份**已插入哨兵的
  `body` 上进行，偏移天然一致，不需要任何区间平移补偿。若放在这些步骤之后，所有已算出的区间都会错位。
- **从后往前插**，避免前面的插入打乱后面的位置。
- **插在开标签之后**（元素内容之前），保证章节起点 = 标题文字的开始处，而不是标题之后。

**步骤 2 —— 解析（`splitMarkedBody:742`）**

```kotlin
private sealed interface BodyPiece {
    data class Text(val value: String) : BodyPiece
    data class Image(val spec: ImageSpec, val inline: Boolean) : BodyPiece
    data class FootnoteRef(val footnoteId: String, val number: Int, val label: String) : BodyPiece
    data class TocAnchor(val tocIndex: Int) : BodyPiece          // 新增
}
```

`when` 增加分支：

```kotlin
MARK_TOC -> value.toIntOrNull()?.let { pieces += BodyPiece.TocAnchor(it) }
```

**步骤 3 —— 记录（`ChapterWriter:1273`）**

```kotlin
val tocOffsets = mutableMapOf<Int, Int>()      // tocIndex -> plainText offset

// pieces() 的 when 新增：
is BodyPiece.TocAnchor -> tocOffsets[piece.tocIndex] = offset
// 注意：不写任何 Block，offset 不推进
```

**这是整个方案的核心安全点**：`TocAnchor` **不产出 Block、`offset` 不推进**，
所以 `plainText` 逐字符不变。

### B.3 两个已确认的安全性问题

**① `flush()` 会把一段文本切成两个 `TextBlock`，会不会多出换行？——不会。**

`splitMarkedBody` 遇到哨兵时先 `flush():752`，把缓冲里的文本作为一个 `BodyPiece.Text` 收尾，
哨兵之后再开一个新的缓冲 → 一个段落变成两个相邻 `TextBlock`。

但 `VirtualReaderPane.layoutBlocks:323-376` 会把**连续的文本类块合并进同一个 run**
（`:350` 把文本块累积进 run，`:334` 用 `run.first().plainStart..run.last().plainEnd` 拼整段），
**只有遇到图片等块级元素才断 run**。所以"一段切成两块"在渲染上**不产生任何换行**，视觉零变化。

**② `isAloneOnLine:794` 会不会被干扰？——不会。**

它只在 `MARK_IMAGE` 分支被调用（`isAloneOnLine(marked, index, end)` 判断块级图 vs 行内图）。
`MARK_TOC` 分支不调用它。且哨兵插在标签之后，即使某天被调用，也只影响那一条的图片判定，与 TOC 无关。

**③ `splitMarkedBody` 的 `when` 没有 `else`**。未知 kind 会被静默丢弃（0 字符），
所以即便 `MARK_TOC` 分支漏写，也只是 TOC 退化为"每文档一章"，**不会崩、不会脏数据**。

### B.4 URL 解码陷阱（必须注意）

`normalizeZipPath:861-876` **内部已经做了** `URLDecoder.decode(href.substringBefore('#'), utf8)`。
所以：

- 求 `docPath`：传**原始 href**（含 `#` 也无所谓，函数自己会 `substringBefore('#')`）。**不要预先 decode**，否则二次解码会把 `%20` 之类还原错。
- 求 `fragment`：`href.substringAfterLast('#')` 后**单独** `URLDecoder.decode(fragment, utf8)`。

### B.5 无 fragment 的条目怎么处理（b2 全量场景）

b2 的 120 条 TOC **全部没有 fragment**，此时 TOC 与 spine 文档 1:1。
处理：这类条目的 offset = **该文档在 plainText 中的起始 offset**。

在 `load()` 写每篇文档前记录 `docStartOffset[entryPath] = writer.offset`，
闭合算法里 `offsetOf(e) = tocOffsets[e.index] ?: docStartOffset[e.docPath]`。

---

## 5. 决策 C：章节区间闭包算法

### C.1 输入

- `entries: List<TocEntry>` —— 决策 A 选中层的条目
- `offsetOf(e): Int?` —— 决策 B 的结果（哨兵 offset，或 docStartOffset，或 null）
- `spineIndexOf(docPath): Int` —— 文档在 spine 中的下标（**排序键的第一维**）
- `totalLength = writer.offset`

### C.2 算法

```
1. 过滤：resolved = entries.mapNotNull { e -> offsetOf(e)?.let { e to it } }
              .filter { (_, off) -> off in 0 until totalLength }
              .filter { (e, _) -> e.docPath 在 spine 中 }        // TOC 指向非 spine 文档 → 丢

2. 排序：按 (spineIndexOf(docPath), offset) 升序稳定排序
         —— nav/ncx 的条目顺序不保证与 spine 一致，必须显式排序

3. 去重：offset 相同的只保留第一条（同一位置被两条 TOC 引用）

4. 单调：若 off <= prevOff 则丢弃（防御跨文档乱序的兜底）

5. 闭包：
   bounds = resolved.map { it.second }
   titles = resolved.map { it.first.title }        // 已做父标题补全
   starts = mutableListOf<Int>(); names = mutableListOf<String>()
   if (bounds.isEmpty() || bounds.first() > 0) { starts += 0; names += "开头" }
   bounds.forEachIndexed { i, off -> starts += off; names += titles[i] }

   chapters = starts.mapIndexedNotNull { i, s ->
       val e = starts.getOrElse(i + 1) { totalLength }
       if (e > s) Chapter(names[i].take(80), s, e) else null
   }
```

### C.3 三个退化兜底（缺一不可）

| 级别 | 条件 | 结果 |
|---|---|---|
| 兜底 1 | 部分 TOC 条目 fragment 定位失败 | 该条目丢弃，其区间**并入前一段**（不产生空洞） |
| 兜底 2 | `resolved` 为空但 spine 有内容（nav/ncx 都缺或全定位失败） | **一篇 spine 一章**，标题用现有 `extractTitle:938`，起点 = `docStartOffset` |
| 兜底 3 | spine 也空 | `Chapter("全文", 0, totalLength)`（现状行为，保持不变） |

> 兜底 2 对 b2 恰好等价（120 章 1:1），所以 b2 即便 nav 解析失败也不会比现状更差。

### C.4 "开头"段

b1/b3 的第一条 TOC 通常不指向文档最开头（前面有封面、出版说明、序言）。
`bounds.first() > 0` 时补一段 `Chapter("开头", 0, bounds.first())`，
避免这段内容无处归属 —— 现状它会被并进第一章，翻目录时显得突兀。

---

## 6. 决策 D：TXT 规则表扩展

### D.1 现状问题定位（`ChapterParser.kt`，仅 51 行）

```kotlin
chapterHeading              :6-7    只认「第N章/节/回/卷/集/部/篇」和「chapter N」
sentencePunctuation         :10     [。！？；，,]  ——「，」在拒绝集里
isChapterTitle              :32     长度 > 40 直接拒
MAX_TITLE_LENGTH            :48     40
MAX_CHINESE_TITLE_SUFFIX_LENGTH :49 24
narrativeConnectorAfterMarker :11   保留
```

**头号误杀**：`sentencePunctuation` 含「，」。三毛《温柔的夜》的「第十二章，温柔的夜」会被直接判为非标题。

### D.2 规则表（R1–R7）

| ID | 名称 | 正则 | 样本 |
|----|------|------|------|
| **R1** | 中文序号章 | `^\s*第[0-9零〇一二两三四五六七八九十百千万]{1,12}[章节回卷集部篇][^\n]{0,60}$` | 第十二章，温柔的夜 |
| **R2** | 中文序号·无"第" | `^\s*[0-9零〇一二两三四五六七八九十百千万]{1,12}[、.．]\s*[^\n]{0,60}$` | 一、稻草人手记 |
| **R3** | 阿拉伯数字行 | `^\s*\d{1,4}[、.．]\s*[^\n]{0,60}$` | 1. 序章 / 12、山 |
| **R4** | 英文 Chapter | `^\s*chapter\s+([0-9]+｜[ivxlcdm]+)[^\n]{0,60}$`（IGNORE_CASE） | Chapter 3 The Road |
| **R5** | 括号序号 | `^\s*[（(]\s*\d{1,4}\s*[)）]\s*[^\n]{0,60}$` | (3) 雨季不再来 |
| **R6** | 非序号固定名 | `^\s*(序言\|序\|自序\|总序\|译序\|推荐序\|前言\|引子\|楔子\|尾声\|后记\|附录\|跋)\s*[:：]?\s*[^\n]{0,20}$` | 序言 / 附录 |
| **R7** | 罗马数字 | `^\s*(?i)(part\|book\|section)?\s*[IVXLCDM]{1,7}[、.．\s][^\n]{0,60}$` | IV. 撒哈拉 |

> R1–R7 用 `RegexOption.MULTILINE` 统一在一个 `findAll` 里跑（或各自 `findAll` 后按 offset 归并去重）。
> **同一行被多条命中时只保留第一条**（优先级 R1 > R2 > … > R7）。
> R1 内部"卷/部/篇"本期**不做分层**，与章同级处理（见 §10 遗留）。

### D.3 守卫表（G1–G7，统一在 `isChapterTitle` 里）

| ID | 规则 | 变更点 |
|----|------|--------|
| **G1** | 长度上限 | `MAX_TITLE_LENGTH` 40 → **60** |
| **G2** | 标点 | **「，」和「,」移出拒绝集**（关键修复）；改为**句末**出现 `。！？：:` 才拒；句中出现 `。！？` 仍拒 |
| **G3** | 句中保护 | 去掉序号前缀后剩余 > `MAX_CHINESE_TITLE_SUFFIX_LENGTH`(24) → 拒（补偿 G1 放宽） |
| **G4** | 叙述性连接 | 保留 `narrativeConnectorAfterMarker`（"第三章里"、"第五章后"），另加"第N章中/时/前/上/下" |
| **G5** | **空行守卫（新增）** | 标题行须独占一行，且满足以下任一：<br>① 是文件首行；② 上一非空行之后是空行；③ 上一非空行以 `。！？」』")）` 结尾 |
| **G6** | **显式拒绝（新增）** | 含 `ISBN`/`http`/`版权`/`目录` 的行 → 拒；<br>`^第.{1,12}[卷部篇].{0,10}第\d{1,4}页$`（索引行）→ 拒 |
| **G7** | **低频词守卫（新增）** | 去前缀后含 ≥3 个 `的/地/得/了/我/你/他/她/它/们` 且剩余长度 > 20 → 拒 |

**G5 的实现**：`findAll` 后拿 `match.range.first`，向前找上一个 `\n`，取该行文本判断。
这是把"正文里的『第一，……』"这类误判压下去的最有效一条。

### D.4 `parse` 的区间闭包（小改）

- 现状 `endOffset = nextStart`、末章 `content.length` —— **保留**（标题行本身包含在章节内，符合直觉）。
- **新增**：命中数 ≥1 且首个命中的 `range.first > 0` 时，在前面补 `Chapter("开头", 0, firstStart)`，
  与 EPUB 侧口径统一。
- **保留**：无命中 → `Chapter("全文", 0, content.length)`。
- 标题清洗：`match.value.trim()` → 折叠连续空白 → 去掉行尾 `。：` → `take(80)`。

---

## 7. 文件清单与改动点

| # | 文件 | 改动 | 说明 |
|---|------|------|------|
| 1 | `src/main/kotlin/com/chen/reader/EpubBookLoader.kt` | **改** | 主战场，见下表 7.1 |
| 2 | `src/main/kotlin/com/chen/reader/ChapterParser.kt` | **改** | 规则表 R1–R7 + 守卫 G1–G7 + `parse` 补"开头"段 |
| 3 | `src/main/kotlin/com/chen/reader/model/Block.kt` | **不改** | 关键：**不新增 Block 类型**，`plainContentOf` 的 `when` 保持封闭 |
| 4 | `src/main/kotlin/com/chen/reader/model/Book.kt` | **不改** | `Book` / `Chapter` 结构不变 |
| 5 | `src/main/kotlin/com/chen/reader/ui/virtual/VirtualReaderPane.kt` | **不改** | `layoutBlocks` 的 run 合并已保证切块不产生换行 |
| 6 | `src/main/kotlin/com/chen/reader/ReaderPanel.kt` | **排查** | 目录下拉/树在 120~200 章量级的性能；预期无需改 |
| 7 | `src/test/kotlin/com/chen/reader/ChapterParserTest.kt` | **新增**（建议） | 覆盖 R1–R7 / G2 逗号修复 / G5 空行守卫 |
| 8 | `src/test/kotlin/com/chen/reader/EpubTocTest.kt` | **新增**（建议） | b1/b2/b3 的章节数断言 + plainText 哈希回归 |

### 7.1 `EpubBookLoader.kt` 逐处改动点

| 位置 | 动作 | 内容 |
|------|------|------|
| `:141` 附近 | **加** | `private const val MARK_TOC = "TOC"` |
| 内部数据结构区（`:1163` 后） | **加** | `private data class TocEntry(val index:Int, val depth:Int, val label:String, val docPath:String, val fragment:String)` |
| 新增函数 | **加** | `readNavigation(opf, manifest, zip): List<TocEntry>` —— ① 找 `properties` 含 `nav` 的 manifest item → 解析 `nav.xhtml` 的 `<nav>`→`<ol>/<li>/<a>`；② 失败则找 `media-type="application/x-dtbncx+xml"` → 解析 `<navPoint>`→`<navLabel><text>` + `<content src>`；③ 都失败返回 emptyList |
| 新增函数 | **加** | `selectTocLevel(entries): List<TocEntry>` —— 决策 A 的 `[8,120]` 最深合格层 + 三条退化 |
| 新增函数 | **加** | `completeTitles(entries): List<String>` —— 决策 A.3 父标题补全 |
| 新增函数 | **加** | `insertTocMarkers(body, entries): String` —— 决策 B 步骤 1，从后往前插 |
| `:249-281` `buildChapterBody` | **改** | 新增 `tocEntries: List<TocEntry>` 参数；`:256` 后插入 `val bodyWithToc = insertTocMarkers(body, tocEntries)`；函数体内后续所有 `body` 换成 `bodyWithToc` |
| `:742-791` `splitMarkedBody` | **改** | `when` 增 `MARK_TOC ->` 分支 |
| `:1259` `BodyPiece` | **改** | 增 `data class TocAnchor(val tocIndex: Int)` |
| `:1273` `ChapterWriter` | **改** | 增 `val tocOffsets = mutableMapOf<Int,Int>()`；`pieces()` 的 `when` 增 `is BodyPiece.TocAnchor -> tocOffsets[piece.tocIndex] = offset` |
| 新增函数 | **加** | `buildChaptersFromToc(...)` —— 决策 C 全文 |
| `:146-198` `load()` | **改** | ① 加载后调 `readNavigation` → `selectTocLevel` → `completeTitles`；② 循环内写每篇前记录 `docStartOffset[entryPath] = writer.offset`；③ 循环后用 `buildChaptersFromToc` 生成 `chapters`（替换现有 `chapters +=`）；④ 三级兜底 |

> **不改动 `readManifest:817`**：ncx 现在会落进 `resources` 分支（因为 `isReadableDocument:852` 对 ncx 返回 false），
> 但 `readNavigation` 会独立重扫 `opf.getElementsByTagNameNS("*","item")`。
> 这样 diff 最小、不动已有资源映射，风险最低。

---

## 8. 三条硬约束申明

### 8.1 `plainText` 逐字符不变 ✅

- `BodyPiece.TocAnchor` 在 `ChapterWriter.pieces()` 里**只记录 offset，不写 Block、不推进 offset**。
- 插入的 `\u0000TOC:i\u0000` 在 `splitMarkedBody` 阶段被完全消耗，**不会进入任何 Block 的内容**。
- 因此 `Book.buildPlainText`（`Book.kt:58`）的拼接结果与 0.6.0 **逐字节相同**。
- **连带收益**：位置恢复三级优先（`restoreOffset` ①globalOffset+anchorText ②globalOffset ③permille）
  全部照旧命中，0.6.0 那次漂移不会重演。

### 8.2 offset ↔ y 不变 ✅

- 哨兵唯一的副作用是 `flush():752` 把一个段落切成两个相邻 `TextBlock`。
- `VirtualReaderPane.layoutBlocks:323-376` 把连续文本类块合并进同一个 run
  （`:350` 累积、`:334` 用 `run.first().plainStart..run.last().plainEnd` 拼整段），
  只有块级元素才断 run → **切块不产生换行，渲染结果零变化**。
- `Book.blockPlainOffsets`（`Book.kt:64`）仍非降序，`blockIndexForOffset` 二分语义不变。

### 8.3 性能与内存 ✅

- TOC 解析只在 `load()` 里做 **1 次**，复杂度 O(条目数)（b3 最大 286 条，可忽略）。
- `nav.xhtml` / `toc.ncx` 各一次 `parseXml`，**不新增对正文文档的 DOM 解析**。
- `insertTocMarkers` 单趟 `StringBuilder`，从后往前插，O(条目数 + body 长度)。
- Block 增量 ≤ TOC 条目数（b3 最多 +181），相对现有数千块的量级可忽略。
- `chapters` 最大 120（受决策 A 上界约束），目录 UI 无压力。

---

## 9. 验收用例

| # | 场景 | 期望 |
|---|------|------|
| V1 | **b1**（作为意志和表象的世界 / 12 spine / 仅 NCX 82 条、无 nav） | 章节数 **71**；标题形如「第一章 · §1」；首条前有「开头」段 |
| V2 | **b2**（深入理解 AI Agent / 15 spine / NCX 286 + nav 290） | 章节数 **92**；以 **nav 为准**；L3 的 181 条被层级规则挡掉 |
| V3 | **b3**（叔本华：意志和表象的世界 / 15 spine / 仅 NCX 14 条、无 nav） | 章节数 **14**（与现状相同）；**标题不再重复**——前几章应为 封二 / 书名页 / 版权页 / 目录 / 导读 … |
| V4 | 回归 | 同一本书在 0.6.0 与 0.7.0 下 `book.plainText` 的 **哈希相等**（断言进 `EpubTocTest`） |
| V5 | TXT | 「第十二章，温柔的夜」被识别为章节标题（G2 移除逗号） |
| V6 | TXT | 正文中的「第一，我们要明白……」**不**被识别（G5 空行守卫） |
| V7 | TXT | 「1. 序章」/「一、稻草人手记」/「Chapter 3」/「(3)」/「序言」全部被识别（R2–R7） |
| V8 | 兜底 | 手工删掉 nav 与 ncx 后打开 b3 → 退化成「一篇 spine 一章」，不崩、不空 |
| V9 | 位置恢复 | 用 0.6.0 保存的进度（globalOffset + anchorText）打开 0.7.0 的同书 → 位置不漂移 |

---

## 10. 遗留问题与假设

1. **假设**：`nav.xhtml` 中存在 `<nav epub:type="toc">`。若只有裸 `<nav>` 而无 `epub:type`，
   取文档中**第一个** `<nav>` 作为目录（`epub:type="landmarks"` / `page-list` 必须排除）。
2. **假设**：spine 文档不会被 `removeRanges` 整篇删除。若某篇被删空，`docStartOffset` 会指向一个空段，
   闭包算法的 `e > s` 判空会自动吞掉它（不产生 0 长度 Chapter）。
3. **R1 内部"卷/部/篇"本期不做分层**：TXT 仍是扁平章列表。
   要不要做「卷 → 章」两级目录，需要 team-lead 决策（涉及 `Chapter` 是否加 `level` 字段，会动 `model/Book.kt`）。
4. **分隔线式章节**（`***` / `———` 之类）本期不做，需要单独一条规则 + 上下文判断，投入产出比低。
5. **b2 的 ncx L1=13 vs nav L1=14** 差 1 条：nav 优先策略下以 14 计；该差异只影响 L1 层统计，
   因最终选中 L2（两者都是 92），**不影响结果**。若将来上界调整，需重新确认。
6. **b1 / b3 无 `nav.xhtml`**：这两本只能走 NCX 通道，因此 NCX 解析**不是可选增强而是必需**。
   实现与验收都要保证"只给一个 NCX 的 EPUB"能正常出章节（V1 / V3 覆盖）。
7. **EPUB 的 TOC 文档本身若在 spine 里**（如 b2 的 nav 页），其文本仍会作为正文出现在开头。
   本期不处理（现状亦然），建议归入后续"目录页剔除"专项。

---

## 11. 决策汇总（team-lead 需确认的 4 项）

| 决策 | 结论 |
|------|------|
| **A 层级选择** | `[8,120]` 取**最深合格层**；b1→L2(71) / b2→L2(92) / b3→L1(14)。**父标题补全要做**（裸「§1」→「第一章 · §1」）。目录来源：**NCX 与 nav 都要支持**（b1/b3 只有 NCX），两者都有时以 nav 为准 |
| **B fragment→offset** | 复用哨兵 `\u0000TOC:i\u0000` → `BodyPiece.TocAnchor` → `ChapterWriter` 只记 offset **不写 Block**。已确认 `isAloneOnLine`、`flush()`、`layoutBlocks` run 合并三处均不受影响 |
| **C 区间闭包** | 按 `(spineIndex, offset)` 排序 → 去重 → 单调修正 → 闭包；首锚点前补「开头」段；三级兜底（丢条目 / spine-per-chapter / 全文） |
| **D TXT 规则** | R1–R7 规则表 + G1–G7 守卫；**核心修复是「，」移出拒绝集** + 新增空行守卫 G5 |

---

## 附录 A：注释汇总的章节归属（修订）

> 主文 §3–§6 把章节粒度从「spine 文档」换成「TOC 条目」，`appendFootnoteSummary` 却仍是
> **每篇文档调一次**，导致一篇文档内的全部注释堆到该文档**最后一个章节**末尾。
> 本附录只解决这一个问题。

### A.1 问题确认与量级

现状 `load()`（约 `:224-236`）：

```kotlin
writer.pieces(splitMarkedBody(chapterBody.text, chapterBody.images, chapterBody.notes))
appendFootnoteSummary(writer, chapterBody.notes)   // ← 按【文档】调用，不是按章节
```

`appendFootnoteSummary`（`:258-269`）写一个 `"\n\n【注释】\n"` 标题 + 每条注释一个 `FootnoteBodyBlock`。

TOC 模式下一篇文档被切成 N 章，于是该文档的**全部**注释落到最后一章。
b1 的量级：全书 296 条注释，其中 `text00006.html` 一篇就有 **63 条**（§1–§9 九个 TOC 章节都在这一篇里）。

**弹窗不受影响**（`hotSpots` 走 `FootnoteRefBlock` + `resolveFootnoteBody`，与章节无关）；
受影响的是**正文里注释段落的落点**——比现状（12 章、注释跟着文档走）明显倒退。

### A.2 结论：按 TOC 章节追加（采纳 team-lead 的倾向，但要去重）

不是"每章追加所有引用落在本章的注释"（那会重复），而是：

> **每章追加「本章区间内首次被引用到」的注释；每条注释在本文档内只写一次；
> 从未被任何引用点引用的注释，兜底写到本文档最后一章末尾。**

去重而非重复的理由见 A.8（重复的注释正文会让 `anchorText` 全书多命中，有跳转风险）。

### A.3 归属判定：不需要给 `EpubFootnote` 补字段

**给 `EpubFootnote` 补"引用点偏移"是错的方向**：注释条目所在的块在 `removeRanges` 阶段就被摘走了，
且 `buildChapterBody` 里每一步（`removeRanges` / `replaceFootnoteRefs` / `replaceImages` /
`stripStructuredMarkup`）都在改写偏移，任何预先算好的位置都要逐步平移补偿，代价大且极易错。

**正确做法 = team-lead 的候选做法，可行**：`ChapterWriter.pieces()` 本来就为每个
`BodyPiece.FootnoteRef` 写一个 `FootnoteRefBlock` —— 引用点已经在这里"路过"了，顺手记账即可，**零额外计算**。

唯一需要补的字段是 `BodyPiece.FootnoteRef` 加一个 `text: String`（注释正文）。
有了它，`flushFootnotes` 就不必反查注释表，`ChapterWriter` 也就不必持有"当前文档的 notes"
这种跨文档的易变状态。

```kotlin
// BodyPiece（约 :1436）
data class FootnoteRef(
    val footnoteId: String,
    val number: Int,
    val label: String,
    val text: String,          // 新增：注释正文，供章末汇总直接写入
) : BodyPiece

// splitMarkedBody 的 MARK_REF 分支
MARK_REF -> notesById[value]?.let { note ->
    pieces += BodyPiece.FootnoteRef(note.id, note.number, note.label, note.text)
}
```

**`EpubFootnote` 一个字段都不用加。**

### A.4 `ChapterWriter` 的改动（核心）

```kotlin
private class ChapterWriter(private val blocks: MutableList<Block>) {
    var offset: Int = 0
        private set
    val tocOffsets = mutableMapOf<Int, Int>()

    /** 本章（自上次 flush 起）出现过的引用点，按出现顺序 */
    private val pending = mutableListOf<BodyPiece.FootnoteRef>()
    /** 本文档已写过的注释 id。footnoteId 只在文档内唯一，故按文档重置 */
    private val writtenInDoc = mutableSetOf<String>()

    fun startDocument() {
        pending.clear()
        writtenInDoc.clear()
    }

    fun pieces(pieces: List<BodyPiece>) {
        pieces.forEach { piece ->
            when (piece) {
                is BodyPiece.Text -> text(piece.value)

                is BodyPiece.Image -> { /* 原逻辑不变 */ }

                is BodyPiece.FootnoteRef -> {
                    /* 原逻辑不变（写 FootnoteRefBlock） */
                    pending += piece                 // ← 新增：记账
                }

                is BodyPiece.TocAnchor -> {
                    flushFootnotes()                 // ← 新增：章节边界，先收掉上一章的注释
                    tocOffsets[piece.tocIndex] = offset
                }
            }
        }
    }

    /** 把"本章首次引用到"的注释追加到章末；没有新注释时**一个字符都不写**。 */
    fun flushFootnotes() {
        val fresh = pending.filter { it.footnoteId !in writtenInDoc }
        pending.clear()
        if (fresh.isEmpty()) return                  // ← 【注释】标题的幂等就在这里
        text("\n\n【注释】\n")
        fresh.forEachIndexed { index, ref ->
            if (index > 0) text("\n")
            footnoteBody(ref.footnoteId, ref.number, ref.text)
        }
        fresh.forEach { writtenInDoc += it.footnoteId }
    }

    /** 文档末尾兜底：把从未被任何引用点引用的注释写掉，避免丢内容。 */
    fun appendOrphanNotes(notes: List<EpubFootnote>) {
        val orphans = notes.filter { it.key !in writtenInDoc }     // 判 key（见 A.8）
        if (orphans.isEmpty()) return
        text("\n\n【注释】\n")
        orphans.forEachIndexed { index, note ->
            if (index > 0) text("\n")
            footnoteBody(note.key, note.number, note.text)         // 用 key（见 A.8）
        }
        orphans.forEach { writtenInDoc += it.key }
    }
}
```

`load()` 的对应改动（约 `:224-236`）：

```kotlin
writer.startDocument()                                        // 新增
if (writer.offset > 0) writer.text("\n\n")
val start = writer.offset
if (!chapterBody.text.startsWith(normalizedTitle)) writer.text("$normalizedTitle\n\n")
writer.pieces(splitMarkedBody(chapterBody.text, chapterBody.images, chapterBody.notes))
writer.appendOrphanNotes(chapterBody.notes)                   // 替换原 appendFootnoteSummary(writer, ...)
```

### A.5 四个必须注意的顺序 / 边界细节

1. **flush 必须在记录 `tocOffsets` 之前**。否则【注释】块会被算进**下一章**，
   下一章的 `startOffset` 落在注释里，目录跳转错。
2. **`writtenInDoc` 按文档重置，不能全书记**。b1 有 15 个 `footnoteId` 跨文档复用，
   全书级去会把后面文档的注释判成"已写过"而整批丢掉。
3. **`isToc` 文档**：`chapterBody.notes` 为空 → `flushFootnotes` 和 `appendOrphanNotes` 都是 no-op，
   不会在目录页后面挂一个空的【注释】。
4. **无 TOC 的兜底路径**：`splitMarkedBody` 产不出 `TocAnchor`，只有文档末尾一次 flush →
   退化为现状（全部注释在文档末尾）。**降级安全。**

### A.6 `【注释】` 标题的幂等 ✅

`flushFootnotes` 在 `fresh.isEmpty()` 时直接 `return`，**连 `\n\n` 都不写**。所以：

- 本章没有引用点 → 无标题、无空行；
- 本章引用点的注释都已在前面章节写过 → 无标题；
- 只有本章有新注释时才出现标题。

### A.7 纯文本口径：确认是 breaking change

| 维度 | 变化 |
|---|---|
| 注释条目的**集合** | **不变**（每条注释恰好写一次，不重复、不丢失） |
| 注释条目的**位置** | 从「文档末尾一堆」变成「分散在各章末尾」 → `plainText` **变** |
| `【注释】` 标题数量 | 从 12（= spine 数）变成 ≤ 71（= 章节数） |
| `FootnoteBodyBlock` 数量 | **不变** |
| 总块数 | 只增加主文 §8.3 里 TocAnchor 切分带来的增量 |
| 同章内输出顺序 | 按**引用点出现顺序**（可选 `sortedBy { it.number }` 改成按编号排，实现期按实测观感定） |

0.9.0 已在升版，此变化是预期内的，**不算回归**。

### A.8 配对键改为全书唯一（根因修法）

#### A.8.1 位置恢复：可用，不需要改（结论不变）

`ReaderPanel.restoreOffset:547` 的三级优先 + `findAnchorNear:568`：

- **① `anchorText`（80 字窗口）**：`findAnchorNear` 先在 `savedOffset ± 3000`（`ANCHOR_SEARCH_RADIUS`，`:1070`）内找，
  找不到就 `content.indexOf(anchor)` **全书兜底**（`:576`）。
  即便注释位置整体重排、偏移全部错位，只要这 80 个字还在书里，就能定位到**正确的文字**。**可用，不改。**
- **② 原始 `globalOffset`**（`:556`）：重排后语义失效，但它排在 ① 之后，实际几乎走不到。
- **③ permille**（`:560`）：章节集合变了所以会偏；但只在「anchorText 为空且 globalOffset == 0」时才用。**可接受，不改。**

#### A.8.2 根因：`footnoteId` 被当成全书唯一键用，但它只在文档内唯一

`footnoteId` 取自 DOM 的 `id`，**只在文档内唯一** —— b1 实测有 **15 个 id 出现在 ≥2 个文档**。
它被直接用作「引用点 ↔ 注释条目」的配对键，于是 `Book.buildHotSpots` 只能靠**位置启发式**去消除歧义：

```kotlin
// model/Book.kt（0.8.1 加的）
val footnoteBodies = blocks.filterIsInstance<FootnoteBodyBlock>().groupBy { it.footnoteId }
...
body = resolveFootnoteBody(footnoteBodies, block)
//   → firstOrNull { it.plainStart >= ref.plainEnd } ?: lastOrNull()
```

**这是一条位置推断，不是配对。** 它成立的前提是"引用点一定排在它的注释条目之前"。
主文附录 A.2 的「注释追加在**首次引用**所在章末尾」一旦落地，**同一条注释在后面章节的第二个引用点**
前面就没有同名 body 了 —— 这个前提是**新设计主动破坏**的。
再往上叠第三层位置补丁（"就近向前"）只会让这条链路继续漏。**不叠补丁，改根因。**

#### A.8.3 事实前提：`footnoteId` 没有任何 UI 消费者

全仓 grep：它只出现在 `model/Block.kt`（`FootnoteRefBlock` / `FootnoteBodyBlock` 的字段声明）、
`model/HotSpot.kt:34`（`FootnoteHotSpot` 字段声明）、`model/Book.kt`（配对用）、
`EpubBookLoader.kt`（构造）。**没有任何界面代码读它**。

它纯粹是「引用点 ↔ 注释条目」的**内部配对键**。既然无外部消费者，就可以换成任意内部键。

#### A.8.4 修法：构造时就限定为文档级唯一

给 `EpubFootnote` 加一个派生属性，**不新增字段、不改 `id` 本身**
（`replaceFootnoteRefs` 还要拿 `id` 去匹配 `<a id="…">`）：

```kotlin
private data class EpubFootnote(
    val docId: String,
    val elementIndex: Int,
    val id: String,          // 保持原样：文档内的 DOM id
    ...
) {
    /** 全书唯一的配对键。`id` 只在文档内唯一（b1 有 15 个 id 跨文档复用），必须带文档维度。 */
    val key: String get() = "$docId#$id"
}
```

**两处产出点改用 `key`**（`id` 保持原样）：

1. `splitMarkedBody` 的 `MARK_REF` 分支：
   ```kotlin
   MARK_REF -> notesById[value]?.let { note ->
       pieces += BodyPiece.FootnoteRef(note.key, note.number, note.label, note.text)
   }
   ```
2. `appendOrphanNotes`（及被它取代的 `appendFootnoteSummary`）：
   ```kotlin
   footnoteBody(note.key, note.number, note.text)
   // 去重集合也一并改为判 key：
   val orphans = notes.filter { it.key !in writtenInDoc }
   orphans.forEach { writtenInDoc += it.key }
   ```
   （`flushFootnotes` 里的 `it.footnoteId !in writtenInDoc` 不用改 ——
   `BodyPiece.FootnoteRef.footnoteId` 已经是 `key` 了。）

#### A.8.5 连带删掉位置推断

`FootnoteRefBlock.footnoteId` / `FootnoteBodyBlock.footnoteId` 从此**全书唯一**，
`Book.buildHotSpots` 可以回到精确匹配，把 0.8.1 的位置启发式**整段删除**：

```kotlin
// model/Book.kt
val footnoteBodies = blocks
    .filterIsInstance<FootnoteBodyBlock>()
    .associateBy { it.footnoteId }        // ← 回到普通 map，groupBy 与 resolveFootnoteBody 一起删
...
body = footnoteBodies[block.footnoteId]?.text.orEmpty()
```

同时删掉 `resolveFootnoteBody` 函数及其上方那段「为什么不能全书级 `associateBy`」的注释
（该注释描述的约束已被 `key` 消除，留着会误导后人）。

**效果**：配对从"猜位置"回到"查表"，与注释写在第几章**完全解耦**。
A.9 里"第二个引用点弹窗显示错内容"的风险由此**彻底消失**，不再需要任何兜底。

> **与 A.5 第 2 条的关系**：改用 `key` 后，`writtenInDoc`「按文档重置」不再是**正确性**要求
> （`key` 已全书唯一，全书级去重也不会误判）。但按文档重置仍然正确、且集合更小，
> **无需改动** —— 已写进 spec 的实现照做即可。

### A.9 备选方案（仅在实测重复度高时才切）

若实现后实测发现「同一条注释被多个章节引用」的比例很高、读者觉得每章都重复一份注释很怪，
可切到「**允许重复**」变体：去掉 `writtenInDoc` 去重，每章都写本章引用到的全部注释。

**切换前必须确认的代价**：重复的注释正文会让 `anchorText` 在全书有 ≥2 个命中点，
而 `findAnchorNear` 的 `content.indexOf` 取**第一个** —— 若用户当时读的位置正好在注释区，
可能跳回前一章的同名注释。

→ **默认用「去重」版本（A.2）；重复版本需要实测数据支撑才切。**

### A.10 TXT 侧：确认不受影响 ✅

`TxtBookLoader.load():22-49` 把整本读成一个字符串，
`blocks = listOf(TextBlock(0, content.length, content, LineStyle.BODY))`（`:39`），
章节只由 `ChapterParser.parse(content)`（`:40`）切分。

它**不经过 `ChapterWriter`、不产生 `FootnoteBodyBlock`、不调 `appendFootnoteSummary`**。

→ team-lead 的判断正确：TXT 侧零影响。TXT 的 `plainText` 在 0.7.0 **完全不变**
（决策 D 只改 `chapters` 的边界，而 `chapters` 不参与 `plainText`）。

### A.11 改动清单（本附录相对主文 §7 的增量）

| 文件 | 位置 | 动作 |
|---|---|---|
| `EpubBookLoader.kt` | `BodyPiece.FootnoteRef`（约 `:1436`） | **加** `text: String` |
| `EpubBookLoader.kt` | `splitMarkedBody` 的 `MARK_REF` 分支 | 传入 `note.text` |
| `EpubBookLoader.kt` | `ChapterWriter`（约 `:1473`） | **加** `pending` / `writtenInDoc` / `startDocument()` / `flushFootnotes()` / `appendOrphanNotes()`；`pieces()` 的 `FootnoteRef` 分支记账、`TocAnchor` 分支先 flush |
| `EpubBookLoader.kt` | `load()`（约 `:224-236`） | 调 `startDocument()`；`appendFootnoteSummary(...)` → `appendOrphanNotes(...)` |
| `EpubBookLoader.kt` | `appendFootnoteSummary`（`:258-269`） | **删除**（逻辑并入 `ChapterWriter`） |
| `model/Book.kt` | `resolveFootnoteBody`（`:133`） | **加 1 行**「就近向前」兜底；更新 `:126` 的布局保证注释 |
| `model/Block.kt` | — | **不改** |
| `TxtBookLoader.kt` | — | **不改** |
| `ReaderPanel.kt` | — | **不改**（`restoreOffset` 三级优先 + 全书兜底已足够） |

### A.12 验收补充（接主文 V1–V9）

| # | 场景 | 期望 |
|---|---|---|
| **V10** | b1 `text00006.html`（63 条注释 / 9 个 TOC 章节） | 注释分散到 9 章末尾，不再集中到 §9；**不重复、不丢失** |
| **V11** | b1 全书 | `FootnoteBodyBlock` 数量 **== 296**；`【注释】` 出现次数 ≤ 章节数 |
| **V12** | 某章无注释 | 该章末尾**不出现** `【注释】` 标题，也不多出空行 |
| **V13** | 弹窗 | 同一条注释被多次引用时，**每个**引用点弹窗显示的正文都正确（验证 A.8 兜底） |
| **V14** | 降级 | 删掉 nav/ncx 后打开 b3 → 注释回到「文档末尾一堆」，与 0.6.0 一致 |
| **V15** | 跨文档 id 复用 | b1 的 15 个重复 id 不串味：doc A 的引用点不显示 doc B 的注释正文 |
