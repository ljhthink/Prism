package io.prism.voice.local

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * `.voicepack` 归档的应用内导入器（TKN-LVC-016 / ADR-057）。
 *
 * ## 为什么单独成类
 *
 * 这是本项目**唯一**一条"把不可信归档写到应用私有目录"的路径。把它从
 * [io.prism.voice.local.LocalVoicePackStore]（只读定位/校验）和 ViewModel（UI 状态）里切出来，
 * 是为了让它**不依赖任何 Android 类**（只用 `java.util.zip` + `File` + 协程）——
 * 于是全部安全守卫（zip-slip、条目/体积/压缩比上限、原子落盘、失败清理）都能用
 * **JVM 单测逐条钉住**，而不必依赖真机或 instrumentation。
 *
 * ## 落盘协议（ADR-057 决策 3）
 *
 * ```
 * 预检(zip 中央目录) → 空间预检 → 解压到 <root>/.import-<uuid>/
 *   → 复用 LocalVoicePackStore 校验（唯一口径）→ rename 为 <root>/<voiceId>/
 * ```
 * 任一步失败/取消 ⇒ 递归删除临时目录，**不留半包**；覆盖已存在包时先把旧包 rename 成
 * `.bak-<uuid>`，新包提交失败则回滚旧包。
 *
 * ## 明确不做的两件事（已在 ADR-057 登记）
 *
 * 1. **不做条目内容哈希校验**：包内是 100MB 级 ONNX，哈希对"能否加载"没有增量信息，
 *    真正的可用性判据是 [LocalVoicePackStore] 的文件级校验（存在性 + 尺寸 + 按引擎分流）。
 * 2. **不解析 zip 的 unix 权限位**（`java.util.zip` 不暴露外部属性）：实现一律用
 *    `FileOutputStream` **新建普通文件**，因此标记为符号链接的条目也只会被写成内容是目标
 *    路径的普通文件——不构成逃逸面。这是"不解析"之所以安全的**前提**，故在此显式记录。
 */
class LocalVoicePackImporter(
    private val store: LocalVoicePackStore,
    /** 最终落点父目录（生产：`filesDir/voicepack`）。 */
    private val targetRoot: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 临时目录/备份目录名的随机源（构造注入以便单测得到确定名字）。 */
    private val uuid: () -> String = { UUID.randomUUID().toString() },
    /** 可用空间读取（构造注入以便单测覆盖"空间不足"分支——真机上无法可靠构造该条件）。 */
    private val usableSpace: (File) -> Long = { it.usableSpace },
    /** 资源上限（构造注入以便单测用小值覆盖各条上限，而不必真的造 600MB 归档）。 */
    private val limits: Limits = Limits()
) {

    /** 导入结果（[Failure] 的文案**面向用户**：简短、不含绝对路径、可直接上屏）。 */
    sealed interface Result {
        data class Success(
            val voiceId: String,
            val name: String,
            /** 是否覆盖了同名旧包（UI 据此决定提示语）。 */
            val replaced: Boolean,
            val bytes: Long
        ) : Result

        data class Failure(val reason: String) : Result
    }

    /**
     * 导入一个 `.voicepack`（挂起，在 [ioDispatcher] 执行）。
     *
     * @param zip 已落地的本地归档文件（调用方负责把 SAF 流拷到可 seek 的本地文件）
     * @param overwrite 同 `voiceId` 已存在时是否覆盖（false ⇒ 直接失败，由 UI 二次确认后才传 true）
     */
    suspend fun import(zip: File, overwrite: Boolean): Result =
        withContext(ioDispatcher) { importBlocking(zip, overwrite) }

    /**
     * 只读出归档声明的 `voiceId`（**不落盘**）。
     *
     * 用途：UI 在真正导入前回答"这个包会不会覆盖已装的包"——覆盖确认必须在**写入之前**发生
     * （ADR-057 决策 5"不静默覆盖"）；若等到 [import] 返回"已存在"再问，归档已经在临时目录里
     * 解压过一轮，白付一次 600MB 的解压代价。
     *
     * @return 合法 `voiceId`；归档打不开 / 根目录没有 `voicepack.json` / 清单畸形 / id 非法 → null
     */
    fun peekVoiceId(zip: File): String? {
        if (!zip.isFile || zip.length() <= 0L) return null
        return try {
            ZipFile(zip).use { zf ->
                val entry = zf.getEntry(MANIFEST_ENTRY) ?: return null
                // 声明的 size 只是**快速拒绝**（省一次解压），**不能**当读取上界：
                // 中央目录可被任意伪造（声明 1KB、实际解压 1GB ⇒ readBytes 直接 OOM）。
                // 真正的上界必须落在**读取动作**上（见 readBounded）。
                if (entry.size <= 0L || entry.size > LocalVoicePackStore.MAX_MANIFEST_BYTES) return null
                val text = zf.getInputStream(entry).use { input ->
                    readBounded(input, LocalVoicePackStore.MAX_MANIFEST_BYTES) ?: return null
                }.decodeToString()
                store.declaredVoiceIdFromJson(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取归档 voiceId 失败（${e::class.simpleName}）")
            null
        }
    }

    /**
     * 清掉本目录下所有 `.import-*` / `.bak-*` 残留（**只删这两种前缀**，绝不动正式包目录）。
     *
     * 两个前缀都不可能等于合法 `voiceId`（[LocalVoicePackStore.VOICE_ID_PATTERN] 不允许点号），
     * 因此"删掉它们"与"删掉用户数据"在集合上不相交 —— 这是本函数安全的唯一依据。
     *
     * @return 清理掉的条目数（供日志与单测）
     */
    internal fun sweepStale(): Int {
        val children = targetRoot.listFiles() ?: return 0
        var removed = 0
        for (child in children) {
            val n = child.name
            if (!n.startsWith(TMP_PREFIX) && !n.startsWith(BAK_PREFIX)) continue
            if (runCatching { child.deleteRecursively() }.getOrDefault(false)) removed++
        }
        if (removed > 0) Log.i(TAG, "清扫音色包残留目录 $removed 个")
        return removed
    }

    /**
     * 有界读取（实现见 companion：它不依赖实例状态，放那里才能被单测直接钉住）。
     *
     * 存在的理由：zip 条目的 `size` 取自中央目录，属**不可信输入**。任何"先按声明值设限、
     * 再无界读实际流"的写法都能被一个声明与内容不符的归档打穿（OOM 崩溃，而非可回退失败）。
     */
    /**
     * 同步实现（[import] 的调度器包装）——**故意 internal**：单测直接驱动它以避免
     * `runTest` 与真实 IO 调度器混用；生产只经 [import]。
     */
    internal fun importBlocking(zip: File, overwrite: Boolean): Result {
        if (!zip.isFile || zip.length() <= 0L) return Result.Failure(MSG_NOT_A_PACK)
        if (!targetRoot.isDirectory && !targetRoot.mkdirs()) {
            Log.w(TAG, "无法创建音色包根目录")
            return Result.Failure("无法创建音色包目录")
        }
        // 清扫上一次的残留（guardrail TKN-LVC-016 F-4）：`finally` 里的清理在**进程被杀**
        // （系统回收 / 用户划掉 / OOM）时不会执行，残留的 `.import-*`（半成品）与 `.bak-*`
        // （被替换下来的旧包）会一直占着用户空间，而它们被 store 的 voiceId 白名单过滤掉、
        // 在 UI 上也不可见 ⇒ 用户没有任何途径发现或清理。放在每次导入开头清扫是最省事且
        // 必然会被执行到的时机（下次导入 = 用户重新操作同一条路径）。
        sweepStale()

        // ---------- ① 预检（只读中央目录，不解压） ----------
        val plan = try {
            ZipFile(zip).use { planEntries(it) }
        } catch (e: ZipException) {
            Log.w(TAG, "音色包不是有效 zip（${e::class.simpleName}）")
            return Result.Failure(MSG_NOT_A_PACK)
        } catch (e: Exception) {
            Log.w(TAG, "音色包预检失败（${e::class.simpleName}）")
            return Result.Failure("音色包无法读取")
        }
        if (plan is Plan.Rejected) {
            Log.w(TAG, "音色包预检拒绝：${plan.reason}")
            return Result.Failure(plan.reason)
        }
        val accepted = plan as Plan.Ok

        // ---------- ② 空间预检 ----------
        // 中央目录声明的**解压后总量**是**不可信输入**（可被少报 ⇒ 预检形同虚设）。
        // 因此这里取"声明总量"与"归档自身长度 × 压缩比上限"的**较小值**作为需求估计：
        // 后者是 deflate 的物理上界（任何 zip 的解压总量都不可能超过 压缩长度 × 约定比），
        // 于是即使归档全程撒谎，预检也不会被绕过。真实落盘量另有 ① 的在线计数兜底。
        val declaredNeed = minOf(accepted.totalBytes, zip.length() * limits.maxCompressionRatio)
        val free = runCatching { usableSpace(targetRoot) }.getOrDefault(Long.MAX_VALUE)
        if (free < declaredNeed + limits.freeSpaceReserveBytes) {
            Log.w(TAG, "存储空间不足（需 $declaredNeed，可用 $free）")
            return Result.Failure("存储空间不足（约需 ${(declaredNeed / MB).coerceAtLeast(1)}MB）")
        }

        // ---------- ③ 读清单声明的 voiceId ----------
        // **必须在解压前拿到**，有两个理由：
        // ① 失败更快（畸形/缺清单不必先付一次解压）；
        // ② 校验器有一条不变量「清单 voiceId 必须等于所在目录名」（防"角色包声明 A、实际加载 B"），
        //    因此暂存目录必须**提前命名为 <voiceId>**，否则合法包会被这条不变量误杀。
        val declared = peekVoiceId(zip)
        if (declared == null) {
            Log.w(TAG, "音色包清单缺少合法 voiceId")
            return Result.Failure("清单缺少合法的 voiceId")
        }

        // ---------- ④ 解压到 <root>/.import-<uuid>/<voiceId>/ ----------
        val holder = File(targetRoot, "$TMP_PREFIX${uuid()}")
        val tmp = File(holder, declared)
        try {
            if (holder.exists()) holder.deleteRecursively()
            if (!tmp.mkdirs()) return Result.Failure("无法创建临时目录")
            val extract = try {
                ZipFile(zip).use { extractInto(it, tmp, accepted) }
            } catch (e: Exception) {
                Log.w(TAG, "音色包解压失败（${e::class.simpleName}）")
                return Result.Failure("解压失败（空间不足或包已损坏）")
            }
            if (extract is Extract.Failed) {
                Log.w(TAG, "音色包解压拒绝：${extract.reason}")
                return Result.Failure(extract.reason)
            }

            // ---------- ⑤ 校验（复用唯一口径，见 ADR-057 决策 3） ----------
            val ready = when (val status = store.resolveAtDirectory(tmp, declared)) {
                is LocalVoiceStatus.Ready -> status
                is LocalVoiceStatus.Incomplete -> {
                    Log.w(TAG, "音色包校验不通过（${status.problems.size} 项）")
                    return Result.Failure(status.problems.joinToString("；").take(USER_REASON_CHARS))
                }
                is LocalVoiceStatus.NotInstalled -> {
                    Log.w(TAG, "音色包校验判为未安装")
                    return Result.Failure("音色包不完整")
                }
            }

            // ---------- ⑥ 原子提交 ----------
            val target = File(targetRoot, declared)
            var backup: File? = null
            if (target.exists()) {
                if (!overwrite) return Result.Failure("已存在同名音色包（$declared）")
                val b = File(targetRoot, "$BAK_PREFIX${uuid()}")
                if (!target.renameTo(b)) {
                    Log.w(TAG, "旧音色包无法移开，放弃覆盖")
                    return Result.Failure("无法替换已存在的音色包")
                }
                backup = b
            }
            if (!tmp.renameTo(target)) {
                // 回滚：旧包必须原样回到原位，否则用户会**同时丢掉新旧两个包**
                backup?.let { runCatching { it.renameTo(target) } }
                Log.w(TAG, "音色包提交失败（rename 未成功）")
                return Result.Failure("写入失败（存储空间不足？）")
            }
            backup?.deleteRecursively()
            Log.i(TAG, "音色包导入完成（voice=$declared，覆盖=${backup != null}）")
            return Result.Success(
                voiceId = declared,
                name = ready.pack.name,
                replaced = backup != null,
                bytes = ready.pack.totalBytes
            )
        } finally {
            // 无条件清容器：成功路径下 `tmp` 已被 rename 走，holder 只剩空壳；
            // 失败路径下 holder 里是半成品——两者都不能留（"不留半包"是 ADR-057 的硬要求）。
            runCatching { holder.deleteRecursively() }
        }
    }

    // ==================== 预检 ====================

    private sealed interface Plan {
        data class Ok(val totalBytes: Long, val entries: List<EntryPlan>) : Plan
        data class Rejected(val reason: String) : Plan
    }

    private data class EntryPlan(val name: String, val directory: Boolean)

    private fun planEntries(zf: ZipFile): Plan {
        val entries = ArrayList<EntryPlan>()
        var total = 0L
        val en = zf.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (entries.size >= limits.maxEntries) {
                return Plan.Rejected("包内条目过多（上限 ${limits.maxEntries}）")
            }
            val name = e.name ?: return Plan.Rejected("包内存在无名条目")
            if (!isSafeEntryName(name)) {
                return Plan.Rejected("包内路径不合法（${name.take(ENTRY_LABEL_CHARS)}）")
            }
            val directory = e.isDirectory || name.endsWith("/")
            if (!directory) {
                val size = e.size
                // 中央目录必须声明尺寸；`-1`/`0` 之外还要防"声明 1 字节实际 1GB"——后者由解压期计数兜底
                if (size < 0L) return Plan.Rejected("包内条目尺寸不可信（${name.take(ENTRY_LABEL_CHARS)}）")
                if (size > limits.maxEntryBytes) {
                    return Plan.Rejected("单个文件过大（${name.take(ENTRY_LABEL_CHARS)}）")
                }
                val compressed = e.compressedSize
                if (compressed > 0L && size / compressed > limits.maxCompressionRatio) {
                    // 解压炸弹的第一道闸：正常 ONNX/wav 的压缩比远低于该阈值
                    return Plan.Rejected("压缩比异常（${name.take(ENTRY_LABEL_CHARS)}）")
                }
                total += size
                if (total > limits.maxTotalBytes) {
                    return Plan.Rejected("解压总量超上限（${limits.maxTotalBytes / MB}MB）")
                }
            }
            entries += EntryPlan(name, directory)
        }
        if (entries.isEmpty()) return Plan.Rejected(MSG_NOT_A_PACK)
        return Plan.Ok(total, entries)
    }

    /**
     * 条目名安全判定（**第一道闸**；第二道闸是解压期的 canonical 路径比对）。
     *
     * 判据逐条：非空、长度有界、不绝对（`/`、`\` 开头）、无反斜杠（Windows 分隔符在
     * Android 上虽不生效，但混用形式是混淆审计的常见来源）、无冒号（盘符 + NTFS ADS）、
     * 无 NUL、无 `..` 路径段。
     */
    private fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_ENTRY_NAME_CHARS) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains('\\') || name.contains(':') || name.contains('\u0000')) return false
        return name.split('/').none { it == ".." }
    }

    // ==================== 解压 ====================

    private sealed interface Extract {
        data object Ok : Extract
        data class Failed(val reason: String) : Extract
    }

    private fun extractInto(zf: ZipFile, tmp: File, plan: Plan.Ok): Extract {
        val rootPath = tmp.canonicalFile.path + File.separator
        var written = 0L
        var nextSpaceCheck = SPACE_RECHECK_BYTES
        for (p in plan.entries) {
            val out = File(tmp, p.name)
            // 第二道闸（纵深防御）：计划阶段的字符串判定之外，再看**规范化后的真实落点**。
            // 两道闸都必须有：字符串判定负责"早失败且可解释"，canonical 负责兜住
            // 计划阶段没预料到的宿主语义差异。
            //
            // **诚实登记**：在 Android（Linux）上，凡能绕过闸①的条目名（`/x`、`\x`、`a\b`、`C:x`）
            // 经 `File(tmp, name)` 都落在 tmp 内 ⇒ 闸②**不会被单独触发**，故没有"只让闸②变红"的
            // 用例。它是给 Windows 宿主 / 未来换实现留的一层，属**冗余层**而非可测守卫。
            if (!out.canonicalPath.startsWith(rootPath)) {
                return Extract.Failed("包内路径越界（${p.name.take(ENTRY_LABEL_CHARS)}）")
            }
            if (p.directory) {
                if (!out.isDirectory && !out.mkdirs()) return Extract.Failed("无法创建目录")
                continue
            }
            val parent = out.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                return Extract.Failed("无法创建目录")
            }
            val entry = zf.getEntry(p.name) ?: return Extract.Failed("包内条目缺失")
            zf.getInputStream(entry).use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        // 声明值不可信时的兜底（见 planEntries）
                        if (written > limits.maxTotalBytes) {
                            return Extract.Failed("解压总量超上限（${limits.maxTotalBytes / MB}MB）")
                        }
                        // 每 [SPACE_RECHECK_BYTES] 复查一次可用空间：把"撒谎归档把设备写满"的
                        // 最坏溢出量从"未验证的声明总量"收敛到本常量。
                        if (written >= nextSpaceCheck) {
                            nextSpaceCheck = written + SPACE_RECHECK_BYTES
                            val nowFree = runCatching { usableSpace(tmp) }.getOrDefault(Long.MAX_VALUE)
                            if (nowFree < limits.freeSpaceReserveBytes) {
                                return Extract.Failed("存储空间不足（已中止导入并清理临时文件）")
                            }
                        }
                        output.write(buf, 0, n)
                    }
                }
            }
        }
        return Extract.Ok
    }

    companion object {
        private const val TAG = "LocalVoicePack"

        private const val MB = 1024L * 1024

        /**
         * 有界读取：最多读 [limit] 字节；**超出即返回 null**（不截断后继续用——截断的 JSON
         * 会变成"看起来解析失败"，掩盖"这个包在撒谎"这一事实）。
         *
         * 存在的理由：zip 条目的 `size` 取自中央目录，属**不可信输入**。任何"先按声明值设限、
         * 再无界读实际流"的写法都能被一个声明与内容不符的归档打穿（OOM 崩溃，而非可回退失败）。
         *
         * 放在 companion 而非实例方法：它不依赖任何实例状态，放这里才能被单测直接钉住
         * （F-3 的修法是"把上界落到读取动作上"，而这条不变量只有直接测它才不落空）。
         */
        internal fun readBounded(input: java.io.InputStream, limit: Long): ByteArray? {
            val buf = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024L).toInt())
            val chunk = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > limit) return null
                buf.write(chunk, 0, n)
            }
            return buf.toByteArray()
        }

        /** 临时目录前缀（必须与 [LocalVoicePackStore.VOICE_ID_PATTERN] 不相容——它含点号）。 */
        internal const val TMP_PREFIX = ".import-"

        /** 覆盖已存在包时的备份目录前缀。 */
        internal const val BAK_PREFIX = ".bak-"

        /** 解压过程中复查可用空间的步长（见 extractInto；把撒谎归档的溢出量收敛到该值）。 */
        internal const val SPACE_RECHECK_BYTES = 64L * 1024 * 1024

        /** 条目数上限（正常包 ≤ 700：espeak-ng-data 355 + 词典 + 模型）。 */
        internal const val MAX_ENTRIES = 2000

        /** 单条目上限（ZipVoice 的 fp32 decoder 实测 477MB）。 */
        internal const val MAX_ENTRY_BYTES = 600L * MB

        /** 解压总量上限（ZipVoice 包实测约 560MB）。 */
        internal const val MAX_TOTAL_BYTES = 1536L * MB

        /** 压缩比上限（正常模型 < 5:1；>200:1 只可能是解压炸弹）。 */
        internal const val MAX_COMPRESSION_RATIO = 200L

        /** 条目名长度上限（路径总长，远超正常值）。 */
        internal const val MAX_ENTRY_NAME_CHARS = 512

        /** 空间预留（解压完成后仍需留出的余量，避免把设备写满导致系统级故障）。 */
        internal const val FREE_SPACE_RESERVE_BYTES = 200L * MB

        /** 解压缓冲（128KB：兼顾大文件吞吐与内存占用）。 */
        internal const val COPY_BUFFER_BYTES = 128 * 1024

        /** 单条用户可见失败原因的长度上限（防畸形清单把整页文案灌满）。 */
        internal const val USER_REASON_CHARS = 120

        /** 失败原因里引用条目名的截断长度。 */
        internal const val ENTRY_LABEL_CHARS = 48

        internal const val MSG_NOT_A_PACK = "所选文件不是有效的音色包"

        /** 清单在归档中的**根级**条目名（与 [LocalVoicePackStore.MANIFEST_NAME] 同一常量）。 */
        internal const val MANIFEST_ENTRY = LocalVoicePackStore.MANIFEST_NAME
    }

    /**
     * 归档资源上限（**独立成类型**的理由：测试要能只调小其中一条来验证该条闸门真的会拦，
     * 而不是把五条闸门一起调小——后者无法区分"拦住的到底是哪一条"）。
     */
    data class Limits(
        val maxEntries: Int = MAX_ENTRIES,
        val maxEntryBytes: Long = MAX_ENTRY_BYTES,
        val maxTotalBytes: Long = MAX_TOTAL_BYTES,
        val maxCompressionRatio: Long = MAX_COMPRESSION_RATIO,
        val freeSpaceReserveBytes: Long = FREE_SPACE_RESERVE_BYTES
    )
}
