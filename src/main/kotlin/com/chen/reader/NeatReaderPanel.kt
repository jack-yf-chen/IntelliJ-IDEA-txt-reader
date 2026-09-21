package com.chen.reader

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.Locale
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

/**
 * Neat Reader 内嵌网页面板。
 *
 * 本面板只做两件事：用 JCEF 承载 `https://www.neat-reader.cn/webapp`，以及把工具窗口宽度
 * 翻译成一个"站点看到的 CSS 视口宽度"可控的缩放档位。缩放只走 JCEF 浏览器级 `zoomLevel`，
 * 不注入任何影响布局的 CSS/DOM 改动（0.4.4 的页面级适配已被证明会让正文过度缩小）。
 *
 * ## 缩放模型（N1）
 *
 * CEF 语义：`zoomFactor = 1.2 ^ zoomLevel`，站点看到的 CSS 视口宽度 `cssWidth = W / zoomFactor`。
 * 也就是说**缩小渲染（zoomFactor < 1）会让站点以为窗口更宽**，反而更容易触发宽屏版式。
 * 用户已拍板 Q4 = 保"不错版"（宁可字小，也不能出现宽屏窄列版式），因此本实现的目标函数是：
 *
 * ```
 * 让 cssWidth 落在 [TARGET_CSS_WIDTH_MIN, TARGET_CSS_WIDTH_MAX] 区间内
 * ```
 *
 * - 窗口比下界还窄 → 缩小渲染，给站点补足 CSS 像素（否则站点窄屏 UI 会被挤坏）；
 * - 窗口比上界还宽 → **放大**渲染，把 CSS 像素压回上界以内（这才是"不错版"的正确方向，
 *   与 0.4.6 的"最大 96%"相反）。
 *
 * 选档分三步：算理想倍率 → 量化到 `ZOOM_LEVEL_STEP` 网格 → 迟滞判定。
 * 相邻档位相差 `1.2^0.4 ≈ 7.6%`，肉眼可辨，解决 0.4.6「四档只差 3 个百分点、调了没反应」的问题。
 */
class NeatReaderPanel(project: Project) : JPanel(BorderLayout()), Disposable {
    private val stateService = ReaderStateService.getInstance(project)

    private var browser: JBCefBrowser? = null
    private var browserCreated = false
    private var centerComponent: Component? = null
    /** 本面板是否已释放；所有延时回调（invokeLater / Timer）都要先过这道闸。 */
    private var disposed = false

    private val zoomLabel = JLabel()
    private var currentZoomLevel = DEFAULT_ZOOM_LEVEL
    /** 是否已经把 `currentZoomLevel` 真正下发给过当前这个浏览器实例。换浏览器实例时要重置。 */
    private var zoomApplied = false
    /** 手动锁定：true 时不再随窗口宽度自动换档（N4，持久化到 `ReaderState`）。 */
    private var zoomLocked = false

    /** 已成功注入脚本的 URL；URL 变化才重新注入（N3）。 */
    private var injectedUrl: String? = null
    private var injectAttempts = 0
    private var jsQuery: JBCefJSQuery? = null

    /** resize 防抖：拖窗期间只重启定时器，不触碰 zoom（N2）。 */
    private val resizeDebounceTimer = Timer(RESIZE_DEBOUNCE_MS) {
        if (!disposed) {
            applyAutoZoom("resize 防抖")
        }
    }.apply {
        isRepeats = false
    }

    /** 注入校验探针的延时定时器；dispose 时要停掉，避免对已释放的浏览器执行脚本。 */
    private var injectProbeTimer: Timer? = null

    init {
        border = JBUI.Borders.empty(8)
        add(createToolbar(), BorderLayout.NORTH)
        Disposer.register(project, this)
        restoreZoomState()
        // N5：构造时只放占位组件，真正的 JCEF 浏览器延后到组件挂上（Tab 真正显示）时再创建。
        setCenterComponent(createPlaceholder())
    }

    // ------------------------------------------------------------------ 生命周期

    override fun addNotify() {
        super.addNotify()
        // 再推一帧，避免 JCEF 初始化和工具窗口自身的布局在同一段 EDT 里挤在一起。
        SwingUtilities.invokeLater {
            // 这一帧执行时面板可能已经被释放，不能再注册 Disposable。
            if (!disposed) {
                ensureBrowserCreated()
            }
        }
    }

    private fun ensureBrowserCreated() {
        if (browserCreated || disposed) {
            return
        }
        browserCreated = true
        if (!JBCefApp.isSupported()) {
            setCenterComponent(createUnsupportedPanel())
            return
        }

        val jcefBrowser = JBCefBrowser(NEAT_READER_WEB_APP_URL)
        browser = jcefBrowser
        zoomApplied = false
        injectedUrl = null
        injectAttempts = 0
        Disposer.register(this, jcefBrowser)
        jsQuery = createJsQuery(jcefBrowser)
        installBrowserHandlers(jcefBrowser)
        setCenterComponent(jcefBrowser.component)
        SwingUtilities.invokeLater {
            if (zoomLocked) {
                applyZoom(currentZoomLevel, "恢复手动缩放")
            } else {
                applyAutoZoom("浏览器初始化")
            }
        }
    }

    override fun dispose() {
        disposed = true
        resizeDebounceTimer.stop()
        injectProbeTimer?.stop()
        injectProbeTimer = null
        jsQuery = null
        injectedUrl = null
        browser = null
        centerComponent = null
    }

    // ------------------------------------------------------------------ 工具栏

    private fun createToolbar(): JPanel {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        val openWebAppButton = JButton("Web端")
        val homeButton = JButton("官网")
        val externalButton = JButton("外部打开")
        val zoomOutButton = JButton("缩小")
        val zoomFollowButton = JButton("跟随窗口")
        val zoomInButton = JButton("放大")

        openWebAppButton.toolTipText = "在内嵌浏览器中打开 Neat Reader Web App"
        homeButton.toolTipText = "在内嵌浏览器中打开 Neat Reader 官网"
        externalButton.toolTipText = "使用系统浏览器打开 Neat Reader Web App"
        zoomOutButton.toolTipText = "缩小内嵌网页，并锁定为手动缩放"
        zoomFollowButton.toolTipText = "解锁手动缩放，恢复按工具窗口宽度自动换档"
        zoomInButton.toolTipText = "放大内嵌网页，并锁定为手动缩放"

        openWebAppButton.addActionListener {
            loadInEmbeddedBrowser(NEAT_READER_WEB_APP_URL)
        }
        homeButton.addActionListener {
            loadInEmbeddedBrowser(NEAT_READER_HOME_URL)
        }
        externalButton.addActionListener {
            BrowserUtil.browse(NEAT_READER_WEB_APP_URL)
        }
        zoomOutButton.addActionListener {
            setManualZoom((currentZoomLevel - ZOOM_STEP).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL))
        }
        zoomFollowButton.addActionListener {
            zoomLocked = false
            persistZoomState()
            updateZoomLabel()
            applyAutoZoom("跟随窗口")
        }
        zoomInButton.addActionListener {
            setManualZoom((currentZoomLevel + ZOOM_STEP).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL))
        }

        toolbar.add(openWebAppButton)
        toolbar.add(homeButton)
        toolbar.add(externalButton)
        toolbar.add(zoomOutButton)
        toolbar.add(zoomFollowButton)
        toolbar.add(zoomInButton)
        toolbar.add(zoomLabel)
        return toolbar
    }

    private fun createPlaceholder(): Component {
        return JBLabel("正在准备内嵌浏览器…", SwingConstants.CENTER)
    }

    /** N6：JCEF 不可用时的兜底 UI，提供「重试」和「外部打开」两个出口。 */
    private fun createUnsupportedPanel(): Component {
        val panel = JPanel(BorderLayout(0, JBUI.scale(10)))
        panel.add(
            JBLabel("当前 IDE 运行环境不支持内嵌 JCEF 浏览器，可重试初始化，或在外部浏览器打开 Neat Reader。"),
            BorderLayout.NORTH,
        )

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        val retryButton = JButton("重试")
        val externalButton = JButton("外部打开")
        retryButton.toolTipText = "重新尝试初始化内嵌浏览器"
        externalButton.toolTipText = "使用系统浏览器打开 Neat Reader Web App"
        retryButton.addActionListener {
            browserCreated = false
            ensureBrowserCreated()
        }
        externalButton.addActionListener {
            BrowserUtil.browse(NEAT_READER_WEB_APP_URL)
        }
        buttons.add(retryButton)
        buttons.add(externalButton)
        panel.add(buttons, BorderLayout.CENTER)
        return panel
    }

    private fun setCenterComponent(component: Component) {
        centerComponent?.let { remove(it) }
        centerComponent = component
        add(component, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    // ------------------------------------------------------------------ 浏览器事件

    private fun installBrowserHandlers(jcefBrowser: JBCefBrowser) {
        jcefBrowser.setOpenLinksInExternalBrowser(false)
        jcefBrowser.jbCefClient.addLifeSpanHandler(
            object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    browser: CefBrowser,
                    frame: CefFrame,
                    targetUrl: String,
                    targetFrameName: String,
                ): Boolean {
                    if (targetUrl.isNotBlank() && targetUrl != ABOUT_BLANK) {
                        SwingUtilities.invokeLater {
                            loadInEmbeddedBrowser(targetUrl)
                        }
                    }
                    return true
                }
            },
            jcefBrowser.cefBrowser,
        )
        jcefBrowser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (!frame.isMain) {
                        return
                    }
                    // N3：onLoadEnd 是唯一的注入点，构造时不再盲注。
                    injectSingleWindowScript(jcefBrowser, browser.url)
                    if (!zoomLocked) {
                        SwingUtilities.invokeLater {
                            applyAutoZoom("页面加载完成")
                        }
                    }
                }
            },
            jcefBrowser.cefBrowser,
        )
        // N2：拖窗期间只重启定时器，松手 150 ms 后才真正换档。
        jcefBrowser.component.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                resizeDebounceTimer.restart()
            }
        })
    }

    private fun createJsQuery(jcefBrowser: JBCefBrowser): JBCefJSQuery? {
        return try {
            val query = JBCefJSQuery.create(jcefBrowser as JBCefBrowserBase)
            query.addHandler { value: String ->
                LOG.info("Neat Reader 页面回报: $value")
                // 探针回来说幂等标志没生效：在重试上限内再补一次完整注入。
                if (value.trim() == INJECT_NOT_PATCHED) {
                    SwingUtilities.invokeLater {
                        retryInjection()
                    }
                }
                null
            }
            query
        } catch (error: Throwable) {
            // JS 回报通道用于诊断与注入校验，失败不影响主功能（只是失去实测宽度和重试能力）。
            LOG.warn("Neat Reader JS 回报通道创建失败，页面实测宽度与注入重试将不可用", error)
            null
        }
    }

    private fun loadInEmbeddedBrowser(url: String) {
        if (browser == null) {
            ensureBrowserCreated()
        }
        val jcefBrowser = browser
        if (jcefBrowser == null) {
            // 环境不支持内嵌浏览器，退化为外部打开。
            BrowserUtil.browse(url)
            return
        }
        // URL 变了，之前注入的脚本已经失效，交给 onLoadEnd 重新注入。
        injectedUrl = null
        injectAttempts = 0
        jcefBrowser.loadURL(url)
    }

    // ------------------------------------------------------------------ 缩放档位（N1）

    private fun applyAutoZoom(reason: String) {
        if (zoomLocked) {
            return
        }
        val viewportWidth = browserViewportWidth()
        if (viewportWidth <= 0) {
            return
        }
        applyZoom(selectZoomLevel(viewportWidth, currentZoomLevel), reason)
    }

    /**
     * 选择缩放档位。
     *
     * 1. 先看维持当前档位时站点看到的 CSS 视口宽度是否还在 `[MIN - H, MAX + H]` 内 —— 在就不动，
     *    这是迟滞带的全部实现，也是「临界不再抖动」的关键；
     * 2. 越界了才按"把 cssWidth 拉回区间"反推理想倍率，再量化到 `ZOOM_LEVEL_STEP` 网格。
     *    量化方向要跟约束方向一致：压上界时向"更放大"取整，托下界时向"更缩小"取整，
     *    否则量化误差可能把 cssWidth 又推回断点外侧。
     */
    private fun selectZoomLevel(viewportWidth: Int, currentLevel: Double): Double {
        val cssAtCurrent = viewportWidth / zoomFactorOf(currentLevel)
        val withinLowerBound = cssAtCurrent >= TARGET_CSS_WIDTH_MIN - HYSTERESIS_CSS_PX
        val withinUpperBound = cssAtCurrent <= TARGET_CSS_WIDTH_MAX + HYSTERESIS_CSS_PX
        if (withinLowerBound && withinUpperBound) {
            LOG.debug(
                "Neat Reader 维持档位: viewportWidth=$viewportWidth zoomLevel=${fmt(currentLevel, 2)} " +
                    "cssWidth=${fmt(cssAtCurrent, 0)}（在迟滞带内）",
            )
            return currentLevel
        }

        val targetCssWidth = viewportWidth.coerceIn(TARGET_CSS_WIDTH_MIN, TARGET_CSS_WIDTH_MAX)
        val idealFactor = (viewportWidth.toDouble() / targetCssWidth.toDouble())
            .coerceIn(MIN_ZOOM_FACTOR, MAX_ZOOM_FACTOR)
        val roundUp = viewportWidth > TARGET_CSS_WIDTH_MAX
        val level = quantizeZoomLevel(idealFactor, roundUp)
        // 量化用的是 ceil/floor，正常情况下算出的 cssWidth 一定落在目标区间内；
        // 落在外面只可能是被 MIN/MAX_ZOOM_LEVEL 夹住了，必须点名告警。
        warnIfZoomCapped(viewportWidth, level)
        return level
    }

    /**
     * 缩放被取值范围夹住时明确告警。
     *
     * 这种情况下 `cssWidth` 压不回目标区间，Q4「保不错版」在当前窗口宽度下**已经失效**，
     * 但界面上看不出任何异常 —— 日志是唯一的线索。不告警的话调参的人会误以为是断点猜错了。
     */
    private fun warnIfZoomCapped(viewportWidth: Int, level: Double) {
        val factor = zoomFactorOf(level)
        val cssWidth = viewportWidth / factor
        val overMax = cssWidth > TARGET_CSS_WIDTH_MAX
        val underMin = cssWidth < TARGET_CSS_WIDTH_MIN
        if (!overMax && !underMin) {
            return
        }
        val boundName = if (overMax) "上限" else "下限"
        val fix = if (overMax) {
            "放宽 MAX_ZOOM_LEVEL 或下调 TARGET_CSS_WIDTH_MAX"
        } else {
            "放宽 MIN_ZOOM_LEVEL 或上调 TARGET_CSS_WIDTH_MIN"
        }
        LOG.warn(
            "Neat Reader 缩放已达$boundName，cssWidth 压不回目标区间：viewportWidth=$viewportWidth " +
                "zoomLevel=${fmt(level, 2)} zoomFactor=${fmt(factor, 3)} cssWidth=${fmt(cssWidth, 0)} " +
                "目标区间=[$TARGET_CSS_WIDTH_MIN,$TARGET_CSS_WIDTH_MAX] " +
                "允许档位=[$MIN_ZOOM_LEVEL,$MAX_ZOOM_LEVEL] 允许倍率=[${fmt(MIN_ZOOM_FACTOR, 3)},${fmt(MAX_ZOOM_FACTOR, 3)}]；" +
                "请$fix。注意：此时改目标区间可能无效，根因是缩放上限/下限被夹住。",
        )
    }

    /** 把连续倍率量化到 `ZOOM_LEVEL_STEP` 网格上；`roundUp` 为 true 时向"更放大"方向取整。 */
    private fun quantizeZoomLevel(factor: Double, roundUp: Boolean): Double {
        val rawSteps = ln(factor) / ln(ZOOM_BASE) / ZOOM_LEVEL_STEP
        val steps = if (roundUp) ceil(rawSteps) else floor(rawSteps)
        return (steps * ZOOM_LEVEL_STEP).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)
    }

    private fun applyZoom(zoomLevel: Double, reason: String) {
        val changed = !zoomApplied || zoomLevel != currentZoomLevel
        currentZoomLevel = zoomLevel
        zoomApplied = true
        updateZoomLabel()

        val jcefBrowser = browser ?: return
        if (!changed) {
            return
        }
        jcefBrowser.zoomLevel = zoomLevel

        // 诊断日志（调参的唯一依据）：窗口像素宽 / 缩放倍率 / 推算出的 CSS 视口宽度 / 选中档位。
        val viewportWidth = browserViewportWidth()
        val factor = zoomFactorOf(zoomLevel)
        val cssWidth = if (viewportWidth > 0) viewportWidth / factor else 0.0
        LOG.info(
            "Neat Reader 缩放[$reason] viewportWidth=$viewportWidth zoomLevel=${fmt(zoomLevel, 2)} " +
                "zoomFactor=${fmt(factor, 3)} cssWidth=${fmt(cssWidth, 0)} " +
                "档位=${(zoomLevel / ZOOM_LEVEL_STEP).toInt()} 显示=${zoomPercent(zoomLevel)}% " +
                "目标区间=[$TARGET_CSS_WIDTH_MIN,$TARGET_CSS_WIDTH_MAX] 迟滞=$HYSTERESIS_CSS_PX " +
                "locked=$zoomLocked",
        )
        probePageMetrics()
    }

    private fun setManualZoom(zoomLevel: Double) {
        zoomLocked = true
        persistZoomState()
        applyZoom(zoomLevel, "手动缩放")
    }

    private fun restoreZoomState() {
        zoomLocked = stateService.state.neatReaderZoomLocked
        val savedLevel = stateService.state.neatReaderZoomLevel
        if (zoomLocked && savedLevel != null && !savedLevel.isNaN()) {
            currentZoomLevel = savedLevel.coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)
        }
        updateZoomLabel()
    }

    private fun persistZoomState() {
        stateService.state.neatReaderZoomLevel = currentZoomLevel
        stateService.state.neatReaderZoomLocked = zoomLocked
    }

    private fun updateZoomLabel() {
        val lockHint = if (zoomLocked) "（已锁定）" else ""
        zoomLabel.text = "缩放 ${zoomPercent(currentZoomLevel)}%$lockHint"
    }

    private fun browserViewportWidth(): Int {
        return browser?.component?.width?.takeIf { it > 0 }
            ?: width.takeIf { it > 0 }
            ?: 0
    }

    private fun zoomFactorOf(zoomLevel: Double): Double = Math.pow(ZOOM_BASE, zoomLevel)

    private fun zoomPercent(zoomLevel: Double): Int = (Math.pow(ZOOM_BASE, zoomLevel) * 100).toInt()

    private fun fmt(value: Double, digits: Int): String = "%.${digits}f".format(Locale.US, value)

    // ------------------------------------------------------------------ 脚本注入（N3）

    private fun injectSingleWindowScript(jcefBrowser: JBCefBrowser, loadedUrl: String?) {
        if (disposed) {
            return
        }
        val url = effectiveUrl(loadedUrl) ?: effectiveUrl(jcefBrowser.cefBrowser.url)
        if (url == null) {
            // 页面还没真正加载（about:blank），此刻注入注定落在错误的文档上。
            LOG.debug("Neat Reader 跳过脚本注入：页面 URL 尚未就绪")
            return
        }
        if (url == injectedUrl) {
            return
        }
        injectedUrl = url
        injectAttempts = 0
        runSingleWindowInjection(jcefBrowser, url)
    }

    private fun runSingleWindowInjection(jcefBrowser: JBCefBrowser, url: String) {
        val script = buildString {
            jsQuery?.inject(PROBE_FUNCTION_NAME)?.let { append(it).append('\n') }
            append(SINGLE_WINDOW_SCRIPT)
        }
        jcefBrowser.runJavaScript(script, url, 0)
        scheduleInjectionProbe(jcefBrowser, url)
    }

    /**
     * 注入后延时跑一次探针，确认幂等标志是否真的落在当前文档上。
     *
     * 线程约定：`onLoadEnd` 由 CEF 线程回调，JCEF 允许在该线程直接 `runJavaScript`；
     * 但 Swing 定时器只能在 EDT 上启动，所以这里把定时器启动包进 `invokeLater`。
     * 探针结果经 JS 回报通道回到 Kotlin，未生效时由 `retryInjection()` 决定是否再补一次。
     */
    private fun scheduleInjectionProbe(jcefBrowser: JBCefBrowser, url: String) {
        SwingUtilities.invokeLater {
            if (disposed) {
                return@invokeLater
            }
            injectProbeTimer?.stop()
            injectProbeTimer = Timer(INJECT_VERIFY_DELAY_MS) {
                if (!disposed) {
                    jcefBrowser.runJavaScript(INJECT_PROBE_SCRIPT, url, 0)
                }
            }.apply {
                isRepeats = false
            }
            injectProbeTimer?.start()
        }
    }

    /** 探针确认未生效后的补注；首次注入不计入，最多再补 `MAX_INJECT_ATTEMPTS` 次。 */
    private fun retryInjection() {
        val jcefBrowser = browser
        val url = injectedUrl
        if (disposed || jcefBrowser == null || url == null) {
            return
        }
        if (injectAttempts >= MAX_INJECT_ATTEMPTS) {
            LOG.warn("Neat Reader 脚本注入校验失败，已重试 $MAX_INJECT_ATTEMPTS 次，停止重试（URL=$url）")
            return
        }
        injectAttempts++
        LOG.info("Neat Reader 脚本注入未生效，第 $injectAttempts 次补注（URL=$url）")
        runSingleWindowInjection(jcefBrowser, url)
    }

    /** 读取站点实测视口宽度，用于校准 `TARGET_CSS_WIDTH_MAX`（见调参说明）。 */
    private fun probePageMetrics() {
        val jcefBrowser = browser ?: return
        if (disposed || jsQuery == null) {
            return
        }
        val url = effectiveUrl(jcefBrowser.cefBrowser.url) ?: return
        jcefBrowser.runJavaScript(PAGE_METRICS_PROBE_SCRIPT, url, 0)
    }

    private fun effectiveUrl(url: String?): String? {
        return url?.takeIf { it.isNotBlank() && it != ABOUT_BLANK }
    }

    companion object {
        const val NEAT_READER_HOME_URL = "https://www.neat-reader.cn/"
        const val NEAT_READER_WEB_APP_URL = "https://www.neat-reader.cn/webapp"

        private val LOG = Logger.getInstance(NeatReaderPanel::class.java)

        private const val ABOUT_BLANK = "about:blank"

        // ---------------------------------------------------------------- 缩放档位参数
        //
        // ↓↓↓ 以下 5 个常量就是断点调参的唯一入口，改完重新编译即可，不需要动任何逻辑。↓↓↓
        //
        // 【如何根据日志调参】
        // 1. 打开 IDE 日志（Help -> Show Log in ...），搜索关键字 `Neat Reader 缩放` 与
        //    `Neat Reader 页面回报`。前者每行形如：
        //      viewportWidth=1180 zoomLevel=0.80 zoomFactor=1.157 cssWidth=1020 档位=2 显示=115%
        //    后者回传站点自己看到的实测宽度：
        //      metrics innerWidth=1020 clientWidth=1020 bodyWidth=1020
        // 2. 缓慢拖动工具窗口宽度，每到一个档位就看一眼页面版式是否变成「双列 / 窄正文 + 侧边栏」。
        // 3. 找到**第一次出现宽屏版式**的那一行，记下它的 cssWidth（或 innerWidth），
        //    把 TARGET_CSS_WIDTH_MAX 改成"比它小 40~80"的值（留出迟滞带 + 量化误差的余量）。
        // 4. 反过来，如果窄窗口下正文被挤压或出现横向滚动，记下那一刻的 cssWidth，
        //    把 TARGET_CSS_WIDTH_MIN 改成"比它大 40~80"的值。
        // 5. 若发现档位切换太迟钝/太敏感，调 HYSTERESIS_CSS_PX（默认 40 CSS px）；
        //    若发现相邻档肉眼看不出区别，把 ZOOM_LEVEL_STEP 调大（0.4 ≈ 7.6%，0.6 ≈ 11.6%）。
        //
        // 【排查：宽窗口下"保不错版"失效】
        // 如果日志里出现 `Neat Reader 缩放已达上限，cssWidth 压不回目标区间` 的 WARN，
        // 说明是**缩放倍率被 MIN/MAX_ZOOM_LEVEL 夹住**了，此时改 TARGET_CSS_WIDTH_MAX 是没用的，
        // 必须放宽 MAX_ZOOM_LEVEL（默认 3.0，对应倍率 1.728）。反之窄窗口出现"已达下限"则放宽
        // MIN_ZOOM_LEVEL（默认 -3.0，对应倍率 0.578）。
        //
        // 注意：TARGET_CSS_WIDTH_MAX 是**站点**的断点，和本面板的像素宽度不是一回事；
        // 面板宽度 W 与它的关系是 cssWidth = W / zoomFactor，插件会自动反推 zoomFactor。

        /** 站点窄屏版式可用所需的最小 CSS 视口宽度；窗口更窄时缩小渲染补足。 */
        private const val TARGET_CSS_WIDTH_MIN = 600

        /** 站点切换到「宽屏窄列」版式的 CSS 宽度断点；窗口更宽时放大渲染压住。**必须真机测量**。 */
        private const val TARGET_CSS_WIDTH_MAX = 1024

        /** 迟滞带半宽（CSS px）：当前 cssWidth 只要还在 `[MIN-H, MAX+H]` 内就不换档。 */
        private const val HYSTERESIS_CSS_PX = 40

        /** 档位间隔（zoomLevel 单位）：`1.2^0.4 ≈ 7.6%`，相邻档肉眼可辨。 */
        private const val ZOOM_LEVEL_STEP = 0.4

        /** resize 防抖时长（毫秒）：拖窗期间不换档，松手后再算。 */
        private const val RESIZE_DEBOUNCE_MS = 150

        // ---------------------------------------------------------------- 缩放取值范围

        private const val DEFAULT_ZOOM_LEVEL = 0.0

        /**
         * 缩放取值范围。上下限必须足够宽，否则窗口太宽/太窄时 `cssWidth` 压不进目标区间，
         * Q4「保不错版」会静默失效——夹住时 `warnIfZoomCapped` 会打 WARN 点名。
         * `-3.0 ~ 3.0` 对应倍率 `0.578 ~ 1.728`，即窗口宽到约 1770 px 仍能压进 1024 的目标区间。
         */
        private const val MIN_ZOOM_LEVEL = -3.0
        private const val MAX_ZOOM_LEVEL = 3.0
        private const val ZOOM_STEP = 0.4
        private const val ZOOM_BASE = 1.2
        private val MIN_ZOOM_FACTOR: Double = Math.pow(ZOOM_BASE, MIN_ZOOM_LEVEL)
        private val MAX_ZOOM_FACTOR: Double = Math.pow(ZOOM_BASE, MAX_ZOOM_LEVEL)

        // ---------------------------------------------------------------- 注入脚本

        /** 注入校验最多补几次（首次注入不计入）。 */
        private const val MAX_INJECT_ATTEMPTS = 2

        /** 注入后多久跑一次探针（毫秒）。 */
        private const val INJECT_VERIFY_DELAY_MS = 300

        /** JS 回报通道的函数名；只在页面里存在时才使用。 */
        private const val PROBE_FUNCTION_NAME = "__novelReaderProbe"

        /** 探针回报"幂等标志未生效"时的固定字符串，Kotlin 侧据此触发补注。 */
        private const val INJECT_NOT_PATCHED = "patched=false"

        /**
         * 单窗口脚本：把 `window.open` 和 `target="_blank"` 的链接都改成同 Tab 跳转，
         * 避免云端下载或打开书籍时弹出空白窗口。脚本自身幂等，可安全重复执行。
         */
        private const val SINGLE_WINDOW_SCRIPT = """
            (function () {
              if (window.__novelReaderSingleWindowPatched) return;
              window.__novelReaderSingleWindowPatched = true;
              window.open = function (url, target, features) {
                if (url && typeof url === 'string' && url !== 'about:blank') {
                  window.location.href = url;
                }
                return window;
              };
              document.addEventListener('click', function (event) {
                var target = event.target;
                var link = target && target.closest ? target.closest('a[target="_blank"]') : null;
                if (!link || !link.href) return;
                event.preventDefault();
                window.location.href = link.href;
              }, true);
            })();
        """

        /**
         * 注入校验探针：只读取幂等标志并回报给 IDE 日志，**不自行补注**。
         * 补注由 Kotlin 侧的 `retryInjection()` 统一决定，这样重试次数才是真实可控的。
         */
        private const val INJECT_PROBE_SCRIPT = """
            (function () {
              var patched = !!window.__novelReaderSingleWindowPatched;
              if (window.__novelReaderProbe) {
                window.__novelReaderProbe('patched=' + patched);
              }
            })();
        """

        /** 站点实测视口宽度探针，用于校准 `TARGET_CSS_WIDTH_MAX`。 */
        private const val PAGE_METRICS_PROBE_SCRIPT = """
            (function () {
              if (!window.__novelReaderProbe) return;
              var docElement = document.documentElement;
              window.__novelReaderProbe(
                'metrics innerWidth=' + (window.innerWidth || 0) +
                ' clientWidth=' + (docElement ? docElement.clientWidth : 0) +
                ' bodyWidth=' + (document.body ? document.body.clientWidth : 0)
              );
            })();
        """
    }
}
