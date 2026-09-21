package com.chen.reader.model

/**
 * 可点击热区。
 *
 * 与 [Block] 一样用 `plainStart` / `plainEnd` 表达区间，便于用 offset 直接命中。
 * 弹窗方案下只有两类，且**不再包含 FOOTNOTE_BACK**——注释弹窗数据自足，无需返回、无需跳转。
 */
sealed interface HotSpot {
    val plainStart: Int

    val plainEnd: Int
}

/** 图片热区：左键 → 灯箱；右键 → 另存为 / 外部打开 */
data class ImageHotSpot(
    override val plainStart: Int,
    override val plainEnd: Int,
    val resourceId: String,
    val alt: String,
    val caption: String?,
    val intrinsicWidth: Int,
    val intrinsicHeight: Int,
) : HotSpot

/**
 * 脚注引用热区：左键 → 弹窗显示注释。
 *
 * 弹窗方案下直接内嵌注释正文 `body`，弹窗数据自足，无需跳转。
 */
data class FootnoteHotSpot(
    override val plainStart: Int,
    override val plainEnd: Int,
    val footnoteId: String,
    val number: Int,
    val body: String,
) : HotSpot
