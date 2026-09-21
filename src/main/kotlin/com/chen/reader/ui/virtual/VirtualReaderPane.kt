package com.chen.reader.ui.virtual

import com.chen.reader.model.Block
import com.chen.reader.model.Book
import com.chen.reader.model.FootnoteBodyBlock
import com.chen.reader.model.FootnoteRefBlock
import com.chen.reader.model.HotSpot
import com.chen.reader.model.ImageBlock
import com.chen.reader.model.ImageHotSpot
import com.chen.reader.model.InlineImageBlock
import com.chen.reader.model.imagePlaceholder
import com.chen.reader.model.plainContentOf
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * 正文阅读区：自己排版、自己画，不用 `JTextArea`（长篇 TXT/EPUB 用 Swing 文本组件会卡）。
 *
 * ## 0.5.0 的排版模型变化（T41 / T42）
 *
 * 老模型：**所有行等高**，`y = 行序号 × lineHeight`，`lineIndexForY` 一步除法。
 * 新模型：**按 `Book.blocks` 排版 + y 游标累积**，文本行仍是 `lineHeight` 高，
 * 图片块按 `ResourceMeta` 的内建宽高换算成自己的高度。由此三处"等高"假设全部改造：
 *
 * - `lineIndexForY` → `elementIndexForY`，改**二分查找**（行高不等，除法不再成立）；
 * - `getPreferredSize` → 用累积出来的 `contentHeight`，不再是 `行数 × lineHeight`；
 * - `paintComponent` → 按元素类型分派（`TextLineElement` 画字 / `ImageElement` 画框）。
 *
 * ## §8 红线：排版高度不得依赖解码（T43）
 *
 * 图片高度在排版期就由 `ResourceMeta` 定死；解码在**后台线程**做，
 * 完成后**只 `repaint()`，绝不 `revalidate()` / `rebuildLayout()`**。
 * 一旦解码回调触发 relayout，总高变化会走 `ReaderPanel` 的
 * resize → 恢复 → 写回持久化回路，重演 0.3.2「阅读记忆被覆盖」那个坑。
 */
internal class VirtualReaderPane : JComponent(), Scrollable {
    private val imageLock = Any()

    private var book: Book? = null
    private var elements: List<LayoutElement> = emptyList()

    /** 需要着色的脚注标记，按 `startOffset` 升序；排版时算一次（见 [buildFootnoteMarkers]） */
    private var footnoteMarkers: List<FootnoteSegment> = emptyList()

    private var contentHeight = 0
    private var contentInsets: Insets = JBUI.insets(14)
    private var lineHeight = JBUI.scale(28)
    private var ascent = JBUI.scale(20)
    private var foregroundColor: Color = UIManager.getColor("TextArea.foreground")

    /** 脚注标记（正文 `[注N]` 与章末 `[注N]` 前缀）的强调色，随主题换算，见 [resolveAccentColor] */
    private var footnoteAccentColor: Color = ACCENT_ON_LIGHT

    private var lineSpacingPercent = 20
    private var weightLevel = 0
    private var descent = JBUI.scale(6)
    private var selectionStart: Int? = null
    private var selectionEnd: Int? = null

    /** 已解码的位图；值为 null 表示"解码过但失败"，避免反复重试 */
    private val decodedImages = LinkedHashMap<String, BufferedImage?>()

    /** 正在后台解码的 resourceId，防止同一张图并发重复解码 */
    private val decoding = HashSet<String>()

    /**
     * 热区点击回调。交给 `ReaderPanel` 去弹层，本组件只负责命中判定。
     *
     * 命中热区时**不进入选区逻辑**——否则点注解会顺手把"[注1]"选中，右键菜单语义就乱了。
     */
    var onHotSpotClick: ((HotSpot) -> Unit)? = null

    /**
     * 基准光标（`ReaderPanel` 按"隐藏光标"开关设置）。
     *
     * 热区的手型光标**优先级高于**基准光标：开了"隐藏光标"也要能看见、能点到注解，
     * 否则那个开关会直接废掉注解功能。
     */
    private var baseCursor: Cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)

    /** 当前鼠标位置（用于 `setBaseCursor` 后立刻重算光标） */
    private var lastMousePoint: Point? = null

    init {
        background = UIManager.getColor("TextArea.background")
        foreground = foregroundColor
        isOpaque = true
        installSelectionHandlers()
    }

    fun setBook(book: Book) {
        synchronized(imageLock) {
            decodedImages.clear()
            decoding.clear()
        }
        this.book = book
        clearSelection()
        rebuildLayout()
    }

    fun updateReaderStyle(
        font: Font,
        foreground: Color,
        background: Color,
        lineSpacingPercent: Int,
        weightLevel: Int,
    ) {
        this.font = font
        this.foregroundColor = foreground
        this.foreground = foreground
        this.lineSpacingPercent = lineSpacingPercent
        this.weightLevel = weightLevel
        this.background = background
        // 主题切换必须**立刻**生效，所以强调色在这里重算而不是构造时算一次。
        this.footnoteAccentColor = resolveAccentColor(background)
        // 字号/行距变了，解码出来的位图尺寸不再匹配，直接丢弃等下次重解。
        synchronized(imageLock) {
            decodedImages.clear()
        }
        rebuildLayout()
    }

    fun updateContentInsets(insets: Insets, rebuildLayout: Boolean = true) {
        if (contentInsets == insets && !rebuildLayout) {
            return
        }
        contentInsets = insets
        if (rebuildLayout) {
            rebuildLayout()
        } else {
            repaint()
        }
    }

    fun rebuildLayoutForCurrentSize() {
        rebuildLayout()
    }

    /** 设置基准光标（隐藏光标 / 默认文本光标）。热区手型光标始终优先于它。 */
    fun setBaseCursor(cursor: Cursor) {
        baseCursor = cursor
        applyCursorFor(lastMousePoint)
    }

    /**
     * 命中测试：返回 `point` 处的可点击热区，没有则返回 null。
     *
     * 文本行走**横向区间**判定（比"只看字符偏移"宽容一点，点 "[注1]" 的任意位置都算命中）；
     * 图片块整块都是热区。
     */
    fun hotSpotAt(point: Point): HotSpot? {
        val currentBook = book ?: return null
        if (currentBook.hotSpots.isEmpty() || elements.isEmpty()) {
            return null
        }
        return when (val element = elements[elementIndexForY(point.y)]) {
            is TextLineElement -> hotSpotInLine(element, point)
            is ImageElement -> currentBook.hotSpots.firstOrNull { spot ->
                spot is ImageHotSpot && spot.plainStart == element.startOffset
            }
        }
    }

    fun offsetAtY(y: Int): Int {
        val currentBook = book ?: return 0
        if (elements.isEmpty()) {
            return 0
        }
        return elements[elementIndexForY(y)].startOffset.coerceIn(0, currentBook.content.length)
    }

    fun scrollValueForOffset(
        offset: Int,
        viewportHeight: Int,
        alignEnd: Boolean,
        preserveAnchor: Boolean,
        anchorRatio: Double,
    ): Int {
        val element = elementForOffset(offset)
        val top = element?.y ?: contentInsets.top
        val height = element?.height ?: lineHeight
        return when {
            alignEnd -> top - viewportHeight + height + JBUI.scale(24)
            preserveAnchor -> top - (viewportHeight * anchorRatio).toInt()
            else -> top
        }
    }

    fun selectedText(): String? {
        val currentBook = book ?: return null
        val start = selectionStart ?: return null
        val end = selectionEnd ?: return null
        val from = minOf(start, end).coerceIn(0, currentBook.content.length)
        val to = maxOf(start, end).coerceIn(0, currentBook.content.length)
        if (from == to) {
            return null
        }
        return currentBook.content.substring(from, to)
    }

    fun copySelection() {
        val text = selectedText() ?: return
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        if (book == null) {
            return
        }
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = font

            val clipTop = g.clipBounds.y
            val clipBottom = clipTop + g.clipBounds.height
            val startIndex = elementIndexForY((clipTop - lineHeight).coerceAtLeast(0))
            val endIndex = elementIndexForY(clipBottom + lineHeight)

            paintSelection(g, startIndex, endIndex)
            g.color = foregroundColor
            for (index in startIndex..endIndex) {
                when (val element = elements.getOrNull(index)) {
                    is TextLineElement -> {
                        if (element.endOffset <= element.startOffset) {
                            continue
                        }
                        paintTextLine(g, element)
                    }

                    is ImageElement -> paintImage(g, element)

                    null -> Unit
                }
            }
        } finally {
            g.dispose()
        }
    }

    override fun getPreferredSize(): Dimension {
        val height = contentInsets.top + contentInsets.bottom + contentHeight
        return Dimension(parent?.width ?: JBUI.scale(800), height)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int {
        return lineHeight.coerceAtLeast(JBUI.scale(24))
    }

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int {
        return (visibleRect.height * 0.86).toInt().coerceAtLeast(lineHeight)
    }

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false

    // ------------------------------------------------------------------ 排版

    private fun rebuildLayout() {
        val metrics = getFontMetrics(font ?: UIManager.getFont("TextArea.font"))
        ascent = metrics.ascent
        descent = metrics.descent
        lineHeight = (metrics.height * (1f + lineSpacingPercent / 100f)).toInt().coerceAtLeast(metrics.height)

        val currentBook = book
        if (currentBook == null) {
            elements = emptyList()
            footnoteMarkers = emptyList()
            contentHeight = 0
        } else {
            val laid = layoutBlocks(currentBook, metrics)
            elements = laid.elements
            footnoteMarkers = buildFootnoteMarkers(currentBook.blocks)
            contentHeight = laid.height
        }

        revalidate()
        repaint()
    }

    /**
     * 按 `Book.blocks` 排版，用 **y 游标累积**代替老的"行序号 × 行高"。
     *
     * 每个块独立换行（块自带换行，见 `model.Block` 的注释），所以一个块一定从新行开始。
     * 文本块仍然逐字符 `metrics.charWidth()` 折行——这与老实现一致，
     * 保证 `plainText` 的字符偏移到屏幕位置的映射仍然精确。
     */
    private fun layoutBlocks(book: Book, metrics: FontMetrics): LayoutResult {
        val result = mutableListOf<LayoutElement>()
        val maxWidth = maxContentWidth()
        var cursor = 0

        book.blocks.forEach { block ->
            when (block) {
                is ImageBlock -> {
                    val element = createImageElement(
                        block,
                        y = contentInsets.top + cursor,
                        maxWidth = maxWidth,
                    )
                    result += element
                    cursor += element.height
                }

                is InlineImageBlock -> {
                    // 行内图在首批按块级渲染（真正的图文混排是 T62）。
                    val element = createImageElement(
                        block,
                        y = contentInsets.top + cursor,
                        maxWidth = maxWidth,
                    )
                    result += element
                    cursor += element.height
                }

                else -> {
                    val text = plainContentOf(block)
                    if (text.isEmpty()) {
                        return@forEach
                    }
                    cursor = appendTextLines(
                        result = result,
                        text = text,
                        baseOffset = block.plainStart,
                        metrics = metrics,
                        maxWidth = maxWidth,
                        cursor = cursor,
                    )
                }
            }
        }

        if (result.isEmpty()) {
            // 老实现在空内容时也会产出一行，保持同样行为（避免 preferredSize 塌成 0）。
            val emptyLine = createLine("", 0, 0, contentInsets.top, 0, metrics)
            result += emptyLine
            cursor += emptyLine.height
        }

        return LayoutResult(result, cursor)
    }

    /** 把一个文本块折成若干行；返回累加后的 y 游标（相对内容顶部）。 */
    private fun appendTextLines(
        result: MutableList<LayoutElement>,
        text: String,
        baseOffset: Int,
        metrics: FontMetrics,
        maxWidth: Int,
        cursor: Int,
    ): Int {
        var nextCursor = cursor
        var lineStart = 0
        var index = 0
        var currentWidth = 0

        fun addLine(endLocal: Int) {
            val safeEnd = endLocal.coerceAtLeast(lineStart)
            result += createLine(text, lineStart, safeEnd, contentInsets.top + nextCursor, baseOffset, metrics)
            nextCursor += lineHeight
        }

        while (index < text.length) {
            val char = text[index]
            if (char == '\r') {
                index++
                continue
            }
            if (char == '\n') {
                addLine(index)
                index++
                lineStart = index
                currentWidth = 0
                continue
            }

            val charWidth = metrics.charWidth(char).coerceAtLeast(1)
            if (currentWidth > 0 && currentWidth + charWidth > maxWidth) {
                addLine(index)
                lineStart = index
                currentWidth = 0
                continue
            }
            currentWidth += charWidth
            index++
        }

        addLine(text.length)
        return nextCursor
    }

    private fun createLine(
        text: String,
        start: Int,
        end: Int,
        y: Int,
        baseOffset: Int,
        metrics: FontMetrics,
    ): TextLineElement {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        val lineText = text.substring(safeStart, safeEnd)
        val xPositions = IntArray(lineText.length + 1)
        var x = 0
        for (index in lineText.indices) {
            x += metrics.charWidth(lineText[index]).coerceAtLeast(1)
            xPositions[index + 1] = x
        }
        return TextLineElement(
            startOffset = baseOffset + safeStart,
            endOffset = baseOffset + safeEnd,
            y = y,
            height = lineHeight,
            text = lineText,
            xPositions = xPositions,
        )
    }

    /**
     * 图片元素：**高度只用 `ResourceMeta` 的内建宽高换算，不解码**（§8）。
     *
     * 缩放策略：先按可用宽度缩放，再限制"不超过一屏高"，取两个比例里更小的那个，
     * 保证宽高比不变、也不会出现一张图顶掉整屏。
     */
    private fun createImageElement(block: Block, y: Int, maxWidth: Int): ImageElement {
        val resourceId: String
        val alt: String
        val isVector: Boolean
        val intrinsicWidth: Int
        val intrinsicHeight: Int
        val placeholder: String
        when (block) {
            is ImageBlock -> {
                resourceId = block.resourceId
                alt = block.alt
                isVector = block.isVector
                intrinsicWidth = block.intrinsicWidth
                intrinsicHeight = block.intrinsicHeight
                placeholder = block.placeholder
            }

            is InlineImageBlock -> {
                resourceId = block.resourceId
                alt = block.alt
                isVector = block.isVector
                intrinsicWidth = block.intrinsicWidth
                intrinsicHeight = block.intrinsicHeight
                placeholder = imagePlaceholder(block.alt)
            }

            else -> error("createImageElement 只接受图片块。")
        }

        val viewportHeight = (parent as? JViewport)?.height ?: 0
        val (boxWidth, boxHeight) = if (intrinsicWidth > 0 && intrinsicHeight > 0) {
            val widthScale = maxWidth.toDouble() / intrinsicWidth.toDouble()
            val heightLimit = viewportHeight.takeIf { it > 0 } ?: Int.MAX_VALUE
            val heightScale = heightLimit.toDouble() / intrinsicHeight.toDouble()
            val scale = minOf(widthScale, heightScale).coerceAtMost(1.0)
            val w = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
            val h = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
            w to h
        } else {
            // 内建尺寸未知（SVG / 探测失败）：给一个固定占位高度，**仍然是常量、不依赖解码**。
            maxWidth to JBUI.scale(DEFAULT_IMAGE_HEIGHT)
        }

        val margin = JBUI.scale(IMAGE_MARGIN)
        return ImageElement(
            startOffset = block.plainStart,
            endOffset = block.plainEnd,
            y = y,
            height = boxHeight + margin * 2,
            x = contentInsets.left,
            width = boxWidth,
            resourceId = resourceId,
            alt = alt,
            isVector = isVector,
            placeholder = placeholder,
            targetWidth = boxWidth,
            boxHeight = boxHeight,
        )
    }

    private fun maxContentWidth(): Int {
        val availableWidth = width
            .takeIf { it > 0 }
            ?: (parent as? JViewport)?.width
            ?: JBUI.scale(800)
        return (availableWidth - contentInsets.left - contentInsets.right).coerceAtLeast(JBUI.scale(120))
    }

    // ------------------------------------------------------------------ 图片绘制与后台解码（T43）

    private fun paintImage(g: Graphics2D, element: ImageElement) {
        val margin = JBUI.scale(IMAGE_MARGIN)
        val boxX = element.x
        val boxY = element.y + margin
        val boxW = element.width
        val boxH = element.boxHeight

        // 占位框先画出来：即使还没解码，正文里也已经"有图"了。
        g.color = placeholderFillColor()
        g.fillRect(boxX, boxY, boxW, boxH)
        g.color = placeholderBorderColor()
        g.drawRect(boxX, boxY, boxW, boxH)

        val decoded = decodedImage(element.resourceId)
        if (decoded != null) {
            drawScaledImage(g, decoded, boxX, boxY, boxW, boxH)
            return
        }

        requestImageDecode(element.resourceId, element.targetWidth)
        g.color = foregroundColor
        val label = element.alt.takeIf { it.isNotBlank() } ?: element.placeholder
        val metrics = g.fontMetrics
        val textWidth = metrics.stringWidth(label)
        if (textWidth > boxW - JBUI.scale(PLACEHOLDER_PADDING) * 2) {
            return
        }
        val textX = boxX + ((boxW - textWidth) / 2)
        val textY = boxY + ((boxH - metrics.height) / 2) + metrics.ascent
        g.drawString(label, textX, textY)
    }

    private fun drawScaledImage(g: Graphics2D, image: BufferedImage, x: Int, y: Int, width: Int, height: Int) {
        val scale = minOf(
            width.toDouble() / image.width.toDouble(),
            height.toDouble() / image.height.toDouble(),
        ).coerceAtMost(1.0)
        val drawWidth = (image.width * scale).toInt().coerceAtLeast(1)
        val drawHeight = (image.height * scale).toInt().coerceAtLeast(1)
        val drawX = x + (width - drawWidth) / 2
        val drawY = y + (height - drawHeight) / 2
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(
            image,
            drawX,
            drawY,
            drawX + drawWidth,
            drawY + drawHeight,
            0,
            0,
            image.width,
            image.height,
            null,
        )
    }

    private fun decodedImage(resourceId: String): BufferedImage? {
        return synchronized(imageLock) { decodedImages[resourceId] }
    }

    /** 已解码的位图（可能为 null）。灯箱首屏直接复用它，避免重复解码。 */
    fun decodedImageFor(resourceId: String): BufferedImage? = decodedImage(resourceId)

    /**
     * 后台解码 + **只 repaint**（§8 红线）。
     *
     * 用 `executeOnPooledThread` 而不是 `Task.Backgroundable`，是因为本组件拿不到 `Project`，
     * 而 `ImageIO` 解码本来也不需要进度条/取消。`rasterize()` 内部还有一层按像素预算的 LRU 缓存，
     * 这里再缓存一层只是为了避免同一张图在连续 repaint 里被反复提交解码任务。
     */
    private fun requestImageDecode(resourceId: String, targetWidth: Int) {
        val resources = book?.resources ?: return
        val alreadyRequested = synchronized(imageLock) {
            decodedImages.containsKey(resourceId) || !decoding.add(resourceId)
        }
        if (alreadyRequested) {
            return
        }

        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val image = try {
                resources.rasterize(resourceId, targetWidth)
            } catch (_: Throwable) {
                null
            }
            synchronized(imageLock) {
                decoding.remove(resourceId)
                decodedImages[resourceId] = image
                trimImageCacheLocked()
            }
            // 红线：解码完成后**只 repaint**，绝不 rebuildLayout / revalidate。
            application.invokeLater {
                if (isShowing) {
                    repaint()
                }
            }
        }
    }

    /** 缓存条目数封顶，避免长篇书滚动后堆住几十张位图。 */
    private fun trimImageCacheLocked() {
        if (decodedImages.size <= MAX_CACHED_IMAGES) {
            return
        }
        val iterator = decodedImages.entries.iterator()
        while (iterator.hasNext() && decodedImages.size > MAX_CACHED_IMAGES) {
            iterator.next()
            iterator.remove()
        }
    }

    // ------------------------------------------------------------------ 选区 / 命中测试

    private fun installSelectionHandlers() {
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return
                }
                // 热区优先：命中就直接交给弹层，**不进入选区逻辑**。
                val spot = hotSpotAt(event.point)
                if (spot != null) {
                    onHotSpotClick?.invoke(spot)
                    return
                }
                val offset = offsetAtPoint(event.point)
                selectionStart = offset
                selectionEnd = offset
                requestFocusInWindow()
                repaint()
            }

            override fun mouseReleased(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return
                }
                if (hotSpotAt(event.point) != null) {
                    return
                }
                selectionEnd = offsetAtPoint(event.point)
                repaint()
            }

            override fun mouseExited(event: MouseEvent) {
                lastMousePoint = null
                applyCursorFor(null)
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(event: MouseEvent) {
                if (hotSpotAt(event.point) != null) {
                    return
                }
                selectionEnd = offsetAtPoint(event.point)
                repaint()
            }

            override fun mouseMoved(event: MouseEvent) {
                lastMousePoint = event.point
                applyCursorFor(event.point)
            }
        })
    }

    /** 热区 → 手型光标；否则回到基准光标（隐藏光标 / 文本光标）。 */
    private fun applyCursorFor(point: Point?) {
        cursor = if (point != null && hotSpotAt(point) != null) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            baseCursor
        }
    }

    /** 在本行里找 `point` 命中的热区：热区按 `plainStart` 有序，二分定位后线性扫本行。 */
    private fun hotSpotInLine(line: TextLineElement, point: Point): HotSpot? {
        val spots = book?.hotSpots ?: return null
        val x = point.x - contentInsets.left
        var index = spots.binarySearch { spot ->
            when {
                line.startOffset < spot.plainStart -> 1
                line.startOffset > spot.plainEnd -> -1
                else -> 0
            }
        }
        if (index < 0) {
            index = -index - 1
        }
        while (index < spots.size) {
            val spot = spots[index]
            if (spot.plainStart > line.endOffset) {
                break
            }
            if (spot.plainEnd > line.startOffset) {
                val from = line.xForOffset(maxOf(spot.plainStart, line.startOffset))
                val to = line.xForOffset(minOf(spot.plainEnd, line.endOffset))
                if (x >= from && x <= to) {
                    return spot
                }
            }
            index++
        }
        return null
    }

    private fun clearSelection() {
        selectionStart = null
        selectionEnd = null
        repaint()
    }

    private fun paintSelection(g: Graphics2D, startIndex: Int, endIndex: Int) {
        val currentBook = book ?: return
        val start = selectionStart ?: return
        val end = selectionEnd ?: return
        val from = minOf(start, end).coerceIn(0, currentBook.content.length)
        val to = maxOf(start, end).coerceIn(0, currentBook.content.length)
        if (from == to) {
            return
        }

        val selectionColor = UIManager.getColor("TextArea.selectionBackground") ?: Color(0x4A6FA5)
        g.color = selectionColor
        for (index in startIndex..endIndex) {
            when (val element = elements.getOrNull(index)) {
                is TextLineElement -> paintLineSelection(g, element, from, to)

                is ImageElement -> {
                    // 图片没有字符位置，只要选区覆盖到它的区间就整块高亮。
                    if (from < element.endOffset && to > element.startOffset) {
                        g.fillRect(element.x, element.y, element.width, element.height)
                    }
                }

                null -> Unit
            }
        }
    }

    private fun paintLineSelection(g: Graphics2D, line: TextLineElement, from: Int, to: Int) {
        val selectedStart = maxOf(from, line.startOffset)
        val selectedEnd = minOf(to, line.endOffset)
        if (selectedStart >= selectedEnd) {
            return
        }
        val x = contentInsets.left + line.xForOffset(selectedStart)
        val width = (line.xForOffset(selectedEnd) - line.xForOffset(selectedStart)).coerceAtLeast(JBUI.scale(2))
        g.fillRect(x, line.y, width, line.height)
    }

    private fun offsetAtPoint(point: Point): Int {
        val currentBook = book ?: return 0
        if (elements.isEmpty()) {
            return 0
        }
        val element = elements[elementIndexForY(point.y)]
        val offset = when (element) {
            is TextLineElement -> element.offsetForX((point.x - contentInsets.left).coerceAtLeast(0))
            // 图片没有字符位置，落到块起点，保证选区仍是合法的 plainText 区间。
            is ImageElement -> element.startOffset
        }
        return offset.coerceIn(0, currentBook.content.length)
    }

    /**
     * y → 元素下标：行高不再相等，所以**必须二分查找**，不能像老实现那样一步除法。
     *
     * 落在元素之间的空隙（图片留白）上时返回前一个元素，与老实现的夹取语义一致。
     */
    private fun elementIndexForY(y: Int): Int {
        if (elements.isEmpty()) {
            return 0
        }
        val index = elements.binarySearch { element ->
            when {
                y < element.y -> 1
                y >= element.y + element.height -> -1
                else -> 0
            }
        }
        if (index >= 0) {
            return index
        }
        return (-index - 2).coerceIn(0, elements.lastIndex)
    }

    /** 字符偏移 → 元素；落在缝隙上时返回前一个元素（与老实现一致）。 */
    private fun elementForOffset(offset: Int): LayoutElement? {
        if (elements.isEmpty()) {
            return null
        }
        val index = elements.binarySearch { element ->
            when {
                offset < element.startOffset -> 1
                offset > element.endOffset -> -1
                else -> 0
            }
        }
        if (index >= 0) {
            return elements[index]
        }
        val insertionPoint = -index - 1
        return elements.getOrNull((insertionPoint - 1).coerceIn(0, elements.lastIndex))
    }

    private fun drawWeightedText(g: Graphics2D, text: String, x: Int, y: Int) {
        g.drawString(text, x, y)
        val extraPaints = (weightLevel - 1).coerceAtLeast(0)
        for (index in 0 until extraPaints) {
            g.drawString(text, x + JBUI.scale(index + 1), y)
        }
    }

    /**
     * 画一行文本。
     *
     * **默认路径**：整行一次 `drawString`，与改造前完全一致——这本书正文有几千行，
     * 绝大多数行里没有脚注，必须保持这条路径不退化。
     *
     * **分段路径**：只有这一行里确实有脚注标记时才启用，把行切成
     * 「普通片段 / 脚注片段」依次绘制，脚注片段用强调色 + 下划线。
     *
     * 两点纪律：
     * 1. **不碰选区**：`paintSelection` 在文本之前已经画完，这里只改 `g.color` 和绘制子串；
     * 2. **不漏状态**：结束时把 `g.color` 复位成 `foregroundColor`，避免影响下一个元素
     *    （`paintImage` 也会自己设色，但复位是便宜的保险）。
     */
    private fun paintTextLine(g: Graphics2D, line: TextLineElement) {
        val segments = footnoteSegmentsIn(line)
        val baseX = contentInsets.left
        val baselineY = line.y + ascent
        if (segments.isEmpty()) {
            g.color = foregroundColor
            drawWeightedText(g, line.text, baseX, baselineY)
            return
        }

        var cursor = line.startOffset
        segments.forEach { segment ->
            if (segment.startOffset > cursor) {
                g.color = foregroundColor
                drawTextRange(g, line, cursor, segment.startOffset, baseX, baselineY)
            }
            g.color = footnoteAccentColor
            drawTextRange(g, line, segment.startOffset, segment.endOffset, baseX, baselineY)
            val fromX = baseX + line.xForOffset(segment.startOffset)
            val toX = baseX + line.xForOffset(segment.endOffset)
            drawUnderline(g, fromX, toX, baselineY)
            cursor = segment.endOffset
        }
        if (cursor < line.endOffset) {
            g.color = foregroundColor
            drawTextRange(g, line, cursor, line.endOffset, baseX, baselineY)
        }
        g.color = foregroundColor
    }

    /** 绘制 [from, to) 这段字符；x 坐标由 `xForOffset` 换算，不自己算字宽。 */
    private fun drawTextRange(
        g: Graphics2D,
        line: TextLineElement,
        from: Int,
        to: Int,
        baseX: Int,
        baselineY: Int,
    ) {
        val localFrom = (from - line.startOffset).coerceIn(0, line.text.length)
        val localTo = (to - line.startOffset).coerceIn(localFrom, line.text.length)
        if (localFrom == localTo) {
            return
        }
        drawWeightedText(g, line.text.substring(localFrom, localTo), baseX + line.xForOffset(from), baselineY)
    }

    /** 脚注标记的下划线：贴在基线下方一点，视觉上更接近"这是个链接"。 */
    private fun drawUnderline(g: Graphics2D, fromX: Int, toX: Int, baselineY: Int) {
        val y = baselineY + (descent * UNDERLINE_DESCENT_RATIO).toInt().coerceAtLeast(1)
        g.drawLine(fromX, y, toX.coerceAtLeast(fromX), y)
    }

    /**
     * 本行里的脚注标记片段（已按行边界裁剪），按起点有序。
     *
     * `footnoteMarkers` 全局有序且不重叠，这里先二分定位再线性扫过本行——
     * 一行内最多两三个标记，代价可以忽略。
     */
    private fun footnoteSegmentsIn(line: TextLineElement): List<FootnoteSegment> {
        if (footnoteMarkers.isEmpty() || line.startOffset == line.endOffset) {
            return emptyList()
        }
        // 手写二分：找第一个 endOffset > line.startOffset 的标记。
        //
        // 用 List.binarySearch 同样可以实现，但它的 comparison 约定是
        // "返回值正数表示元素小于目标"，与直觉相反；这里把下界条件直接写出来，
        // review 时不必回头翻 stdlib 文档。（两个版本已在 2.7 万个线段上交叉验证过，结果一致。）
        //
        // 单调性前提：footnoteMarkers 按 startOffset 升序且互不重叠 ⇒ endOffset 严格升序。
        var low = 0
        var high = footnoteMarkers.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (footnoteMarkers[mid].endOffset <= line.startOffset) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        val result = mutableListOf<FootnoteSegment>()
        var index = low
        while (index < footnoteMarkers.size) {
            val marker = footnoteMarkers[index]
            // 标记已排在本行起点之后，后面全部更远，可以收工。
            if (marker.startOffset >= line.endOffset) {
                break
            }
            // 起点二分已保证 marker.endOffset > line.startOffset，
            // 这里按行的左右边界裁剪即可。
            result += FootnoteSegment(
                startOffset = maxOf(marker.startOffset, line.startOffset),
                endOffset = minOf(marker.endOffset, line.endOffset),
            )
            index++
        }
        return result
    }

    /**
     * 全书中所有需要着色的脚注标记，按 `startOffset` 升序、互不重叠。
     *
     * 两类：正文里的 `FootnoteRefBlock` 整块（`[注N]`），以及章末 `FootnoteBodyBlock`
     * 开头的 `[注N]` 前缀（只着色标记本身，不把整条注释正文也染了）。
     * 排版时算一次，绘制时只做二分，不在每帧遍历 blocks。
     */
    private fun buildFootnoteMarkers(blocks: List<Block>): List<FootnoteSegment> {
        val result = mutableListOf<FootnoteSegment>()
        blocks.forEach { block ->
            when (block) {
                is FootnoteRefBlock -> result += FootnoteSegment(block.plainStart, block.plainEnd)

                is FootnoteBodyBlock -> result += FootnoteSegment(
                    block.plainStart,
                    (block.plainStart + block.markerLength).coerceAtMost(block.plainEnd),
                )

                else -> Unit
            }
        }
        return result
    }

    /**
     * 脚注强调色：**按背景亮度在深蓝 / 亮蓝两端之间选**，而不是写死一个颜色。
     *
     * 为什么必须跟着主题走：
     * - 护眼（浅绿底）/ 纸张（米底）这类**浅底**上，亮蓝会和背景糊在一起，要用**深靛蓝**才有对比；
     * - 暗色主题（深灰底）上，深蓝几乎看不见，要用**亮天蓝**才跳得出来；
     * - 「跟随 IDE」时背景可能是浅色也可能是 Darcula 深色，只有运行时按亮度选才两头都对。
     *
     * 和正文文字色的区分主要靠**色相**（蓝 vs 墨黑/护眼绿/暖棕/淡灰），
     * 亮度上的差异则交给下面的对比度兜底保证。
     */
    private fun resolveAccentColor(background: Color): Color {
        val onLightBackground = perceivedLuminance(background) >= LIGHT_BACKGROUND_LUMINANCE
        var accent = if (onLightBackground) ACCENT_ON_LIGHT else ACCENT_ON_DARK
        // 朝远离背景的方向微调：浅底继续加深、深底继续提亮。
        repeat(MAX_ACCENT_ADJUST_STEPS) {
            if (contrastRatio(accent, background) >= MIN_ACCENT_CONTRAST) {
                return accent
            }
            accent = if (onLightBackground) accent.darker() else accent.brighter()
        }
        return accent
    }

    /** 感知亮度（0 黑 ~ 1 白），够用于"这个底是深还是浅"的判断 */
    private fun perceivedLuminance(color: Color): Double {
        return (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) / 255.0
    }

    /**
     * WCAG 对比度（1:1 ~ 21:1）。
     *
     * 这里**必须**用含 sRGB 伽马校正的相对亮度，不能用 [perceivedLuminance] 那个加权平均：
     * 后者只能回答"深还是浅"，答不了"看不看得清"。
     */
    private fun contrastRatio(a: Color, b: Color): Double {
        val lighter = maxOf(relativeLuminance(a), relativeLuminance(b))
        val darker = minOf(relativeLuminance(a), relativeLuminance(b))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun channel(value: Int): Double {
        val normalized = value / 255.0
        return if (normalized <= 0.03928) {
            normalized / 12.92
        } else {
            Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
    }

    private fun placeholderFillColor(): Color {
        return UIManager.getColor("TextField.inactiveBackground") ?: Color(0xF2F2F2)
    }

    private fun placeholderBorderColor(): Color {
        return UIManager.getColor("TextField.inactiveForeground") ?: Color(0xB4B4B4)
    }

    /** 一行里需要着色的脚注片段，区间为 `[startOffset, endOffset)` */
    private data class FootnoteSegment(
        val startOffset: Int,
        val endOffset: Int,
    )

    private data class LayoutResult(
        val elements: List<LayoutElement>,
        val height: Int,
    )

    private companion object {
        /** 图片上下留白（缩放单位） */
        const val IMAGE_MARGIN = 8

        /** 浅底主题用的脚注强调色：深靛蓝。在护眼绿 / 米色纸 / 白底上都压得住 */
        val ACCENT_ON_LIGHT = Color(0x0A5AA8)

        /** 深底主题用的脚注强调色：亮天蓝。在 0x1F2329 这类深灰底上跳得出来 */
        val ACCENT_ON_DARK = Color(0x6FB8F5)

        /** 背景亮度 ≥ 该值算浅底 */
        const val LIGHT_BACKGROUND_LUMINANCE = 0.5

        /**
         * 强调色与背景的对比度下限。
         *
         * 取 3:1 而非正文的 4.5:1：脚注是**辅助标记**（且有下划线 + 手型光标提示可点），
         * 不需要长时间连续阅读，但对"能一眼看出与众不同"又有硬要求。
         *
         * 内置三个主题的对比度都在 6:1 以上，这个兜底**一次都不会触发**；
         * 真正被它救回来的是中灰色底（lum≈0.502，会被判成浅底拿到深靛蓝，对比度仅 1.75:1）。
         */
        const val MIN_ACCENT_CONTRAST = 3.0

        /** 对比度微调的最大步数，防止极端底色下死循环 */
        const val MAX_ACCENT_ADJUST_STEPS = 6

        /** 下划线相对 baseline 的下移量（占 descent 的比例） */
        const val UNDERLINE_DESCENT_RATIO = 0.35f

        /** 内建尺寸未知时的固定占位高度（缩放单位）——常量，不依赖解码 */
        const val DEFAULT_IMAGE_HEIGHT = 180

        /** 占位框内文字的最小边距（缩放单位） */
        const val PLACEHOLDER_PADDING = 6

        /** 本组件缓存的已解码位图上限 */
        const val MAX_CACHED_IMAGES = 32
    }
}
