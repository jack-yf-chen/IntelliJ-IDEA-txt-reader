package com.chen.reader.bookshelf

import com.chen.reader.book.normalizeZipPath
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/** 从 OPF 里解出来的两条元数据：书名与封面资源在 zip 内的路径。 */
data class EpubMeta(
    /** `dc:title`，已做空白折叠；取不到为 null */
    val title: String?,
    /** 封面资源在 zip 内的路径；定位不到为 null */
    val coverZipPath: String?,
    /** 封面资源的 `media-type`；通常 `image/jpeg` / `image/png` */
    val coverMime: String?,
)

/** 封面定位的中间结果：zip 内路径 + mime。 */
private data class CoverRef(val zipPath: String, val mime: String)

/**
 * EPUB 元数据的**自包含**读取器：只解 `META-INF/container.xml` + OPF（通常几 KB），
 * **不读正文、不构造 `EpubResources`、不跑 `EpubBookLoader` 主流程**。
 *
 * 为什么要新写而不是复用：`EpubBookLoader` 里 `readManifest` 只取 `id/href/media-type`，
 * `properties="cover-image"` 目前**根本没被解析过**；而且那些 helper 全是 `private`，
 * 为了复用去放宽可见性就要动既有文件（红线）。所以这里补一个洞，而不是改老代码。
 *
 * **对外永不抛异常**：任何异常都降级为 `null`，调用方退回"无封面 / 用文件名"。
 */
object EpubMetaReader {
    private const val CONTAINER_PATH = "META-INF/container.xml"

    fun read(path: Path): EpubMeta? = runCatching { readInternal(path) }.getOrNull()

    private fun readInternal(path: Path): EpubMeta? {
        ZipFile(path.toFile()).use { zip ->
            val opfPath = findOpfPath(zip) ?: return null
            val opfEntry = zip.getEntry(opfPath) ?: return null
            val opf = parseXml(zip.getInputStream(opfEntry).readBytes()) ?: return null
            val opfDir = opfPath.substringBeforeLast('/', "")
            val cover = findCover(opf, opfDir)
            return EpubMeta(
                title = findTitle(opf),
                coverZipPath = cover?.zipPath,
                coverMime = cover?.mime,
            )
        }
    }

    /** `META-INF/container.xml` → `<rootfile full-path="…">`。 */
    private fun findOpfPath(zip: ZipFile): String? {
        val containerEntry = zip.getEntry(CONTAINER_PATH) ?: return null
        val container = parseXml(zip.getInputStream(containerEntry).readBytes()) ?: return null
        val rootfile = container.getElementsByTagNameNS("*", "rootfile").item(0) as? Element ?: return null
        return rootfile.getAttribute("full-path").takeIf { it.isNotBlank() }
    }

    /** 第一个非空的 `dc:title`。 */
    private fun findTitle(opf: Document): String? {
        val nodes = opf.getElementsByTagNameNS("*", "title")
        for (index in 0 until nodes.length) {
            val text = nodes.item(index)?.textContent?.trim().orEmpty()
            if (text.isNotEmpty()) {
                return text.replace(Regex("\\s+"), " ")
            }
        }
        return null
    }

    /**
     * 封面四级定位，按优先级：
     *
     * 1. `<meta name="cover" content="{id}">` → `manifest[id].href`
     * 2. manifest item 的 `properties` 含 `cover-image` → 该 item 的 href
     * 3. mime 以 `image/` 开头且（id 或 href）含 `cover` / `封面`，忽略大小写
     * 4. 以上都无 → `null`（负结果也要缓存，见 `BookCoverLoader`）
     */
    private fun findCover(opf: Document, opfDir: String): CoverRef? {
        val items = LinkedHashMap<String, ManifestItem>()
        val nodes = opf.getElementsByTagNameNS("*", "item")
        for (index in 0 until nodes.length) {
            val item = nodes.item(index) as? Element ?: continue
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            if (id.isBlank() || href.isBlank()) {
                continue
            }
            items[id] = ManifestItem(
                href = href,
                mime = item.getAttribute("media-type"),
                properties = item.getAttribute("properties"),
            )
        }
        if (items.isEmpty()) {
            return null
        }

        // 1. <meta name="cover" content="id">
        val metas = opf.getElementsByTagNameNS("*", "meta")
        for (index in 0 until metas.length) {
            val meta = metas.item(index) as? Element ?: continue
            if (!meta.getAttribute("name").equals("cover", ignoreCase = true)) {
                continue
            }
            val found = items[meta.getAttribute("content")] ?: continue
            return CoverRef(normalizeZipPath(opfDir, found.href), found.mime)
        }

        // 2. properties 含 cover-image
        items.values.forEach { found ->
            if (found.properties.contains("cover-image", ignoreCase = true)) {
                return CoverRef(normalizeZipPath(opfDir, found.href), found.mime)
            }
        }

        // 3. id 或 href 含 cover / 封面
        items.forEach { (id, found) ->
            if (!found.mime.startsWith("image/", ignoreCase = true)) {
                return@forEach
            }
            val haystack = "$id ${found.href}".lowercase()
            if (haystack.contains("cover") || haystack.contains("封面")) {
                return CoverRef(normalizeZipPath(opfDir, found.href), found.mime)
            }
        }

        return null
    }

    /**
     * 安全 XML 解析：与 `EpubBookLoader.newSecureDocumentBuilder(allowDoctype = true)` 同源
     * （关外部 DTD / SCHEMA、禁用实体展开、空 `EntityResolver` 兜底），
     * 这里为了兼容带 DOCTYPE 的 OPF 不禁用 doctype-decl，但 XXE 仍然取不到外部实体。
     */
    private fun parseXml(bytes: ByteArray): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        // 只有 setter、没有 getter 的 Java 方法，Kotlin 不会合成属性，必须显式调用 setXxx。
        factory.setExpandEntityReferences(false)
        val builder: DocumentBuilder = factory.newDocumentBuilder().apply {
            setEntityResolver(EntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) })
        }
        builder.parse(ByteArrayInputStream(bytes))
    }.getOrNull()

    private class ManifestItem(
        val href: String,
        val mime: String,
        val properties: String,
    )
}
