package io.prism.pet.overlay

import io.prism.pet.asset.PetPackLimits
import java.io.File

/**
 * 批次24（ADR-051 D2/风险表）：桌宠 WebView 的**虚拟资源路由** —— 纯函数，可 JVM 单测。
 *
 * 渲染页面运行在虚拟源 `https://pet.prism.local/` 下，由 `shouldInterceptRequest` 提供两类资源：
 *
 * | 虚拟路径 | 真实来源 | 说明 |
 * | --- | --- | --- |
 * | `/assets/pet/<rest>` | APK `assets/pet/<rest>` | 渲染器本体（HTML/JS/内置库），只读、随包发布 |
 * | `/pets/<id>/<rest>` | `filesDir/pets/<id>/<rest>` | **用户导入**的形象资产，只暴露当前形象目录 |
 *
 * **为什么自实现而不用 `androidx.webkit.WebViewAssetLoader`**：
 * ① 不引入新依赖（`androidx.webkit` 当前不在依赖清单里）；
 * ② [WebViewAssetLoader] 的 `InternalStoragePathHandler` 以 `filesDir` 根为基准，
 *   会把**整个应用私有目录**（含 DataStore、知识库、密钥相关文件）暴露给页面，
 *    而本功能只需要 `pets/<id>/` 一个子目录——白名单范围越小越好。
 *
 * **三道防线**（对齐 ADR-051 风险表与 [io.prism.skill.SkillDownloader] 的既有模式）：
 * 1. 百分号解码（[percentDecode]）**不做** `+` → 空格转换（路径里的 `+` 是字面加号）；
 * 2. 复用 [PetPackLimits.sanitizeRelativePath]（拒绝 `..` / 绝对路径 / 反斜杠 / 控制字符）；
 * 3. `canonicalPath` 必须落在目标根目录内（真实文件系统兜底）。
 *
 * **不做目录列举**：路径必须精确命中一个**普通文件**，目录一律拒绝。
 */
object PetAssetRoutes {

    /** 渲染器本体的路径前缀（对应 APK `assets/pet/`）。 */
    const val ASSETS_PREFIX = "/assets/pet/"

    /** 用户形象资产的路径前缀（对应 `filesDir/pets/`）。 */
    const val PETS_PREFIX = "/pets/"

    /** 请求路径 → APK assets 内的相对路径；不是该前缀返回 null。 */
    fun toAssetPath(path: String): String? {
        if (!path.startsWith(ASSETS_PREFIX)) return null
        val rest = path.removePrefix(ASSETS_PREFIX)
        return PetPackLimits.sanitizeRelativePath(rest)
    }

    /**
     * 请求路径 → 真实文件；不在白名单范围 / 净化失败 / 逃逸根目录 / 不是普通文件 → null。
     *
     * @param petsDir 形象资产根目录（生产：`File(context.filesDir, "pets")`）
     * @param path 虚拟路径（**已百分号编码**，形如 `/pets/cyrene/motions/%E5%8A%A8%E4%BD%9C%236_0.motion3.json`）
     */
    fun resolvePetFile(petsDir: File, path: String): File? {
        if (!path.startsWith(PETS_PREFIX)) return null
        val encoded = path.removePrefix(PETS_PREFIX)
        val decoded = percentDecode(encoded) ?: return null
        val relative = PetPackLimits.sanitizeRelativePath(decoded) ?: return null
        val target = File(petsDir, relative)
        if (!target.isFile) return null
        val canonicalTarget = runCatching { target.canonicalPath }.getOrNull() ?: return null
        val canonicalRoot = runCatching { petsDir.canonicalPath }.getOrNull() ?: return null
        if (!canonicalTarget.startsWith(canonicalRoot + File.separator)) return null
        return target
    }

    /**
     * 按扩展名给出 Content-Type。
     *
     * **必须给对**：WebView 对 `application/octet-stream` 的 `.moc3` 无所谓（走 arraybuffer
     * 读取），但 `.json` 若给错类型，pixi-live2d-display 的 XHR 响应解析会退化为文本路径
     * 甚至直接判失败；`.png` 给错则纹理加载失败。
     */
    fun mimeOf(path: String): String {
        val name = path.substringAfterLast('/').lowercase()
        return when (name.substringAfterLast('.', "")) {
            "html" -> "text/html"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "moc3" -> "application/octet-stream"
            "txt", "md" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * RFC 3986 百分号解码（UTF-8）。
     *
     * 与 `java.net.URLDecoder` 的两点差异都是**有意的**：
     * 1. **不把 `+` 当作空格**——URL 路径里的 `+` 是字面加号，转成空格会导致
     *    "文件名含 `+` 的资产永远 404"；
     * 2. **畸形编码返回 null 而不是抛异常**——调用方据此直接拒绝该请求。
     */
    fun percentDecode(input: String): String? {
        if (input.indexOf('%') < 0) return input
        val bytes = java.io.ByteArrayOutputStream(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%') {
                if (i + 2 >= input.length) return null
                val hi = Character.digit(input[i + 1], 16)
                val lo = Character.digit(input[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write((hi shl 4) or lo)
                i += 3
            } else {
                val chunk = c.toString().toByteArray(Charsets.UTF_8)
                bytes.write(chunk, 0, chunk.size)
                i++
            }
        }
        return runCatching { String(bytes.toByteArray(), Charsets.UTF_8) }.getOrNull()
    }
}
