// 批次22（SZ-102，ADR-047）：Shizuku UserService AIDL —— 在 shell 身份进程中执行受控命令。
// 方法白名单式设计：仅接收完整命令数组（由 ShizukuShell 代码拼装，不接收自由文本）。
// 修复轮三（2026-09-06）：移除 moveTaskToDisplay——真机实证任务迁移后第三方窗口无法挂载
// untrusted 显示面（0 窗口），迁移法整体废除，改为 shell `am start --display` 直启 + 窗口验证。
package io.prism.phonecontrol.bg;

interface IShizukuUserService {
    /**
     * 以 shell 身份执行命令并等待退出。
     *
     * @param commands 命令与参数（由 ShizukuShell 拼装）
     * @return 退出码（0=成功；非 0/异常=-1）
     */
    int exec(in String[] commands);

    /**
     * 批次22 修复轮八（2026-09-07）：以 shell 身份执行命令并捕获 stdout+stderr（utf-8）。
     * 用途：`settings get global overlay_display_devices` 启动对账（读残留设置值，仅在
     * 精确匹配 Prism 规格 string 时清除，避免误删用户自开的模拟副屏配置）。
     *
     * @param commands 命令与参数（由 ShizukuShell 拼装）
     * @return stdout+stderr 合并输出（命令失败/异常返回 null）
     */
    String execWithOutput(in String[] commands);

    /**
     * ADR-065（US-3114）：在本 shell 进程内创建**隐藏虚拟屏**（DisplayBackend 默认后端）。
     *
     * 必须在创建者进程内自持 ImageReader surface——跨进程传 Surface 不可靠；因此本方法
     * 不接收 Surface，由服务自行创建并持有。尺寸/DPI 由调用方指定（与执行面规格一致）。
     *
     * @return `OK:<displayId>:<flagsHex>` 或 `ERR:<异常类名>:<消息>`（错误文案用于诚实降级）
     */
    String createHiddenDisplay(int width, int height, int density);

    /**
     * 释放 [createHiddenDisplay] 创建的隐藏虚拟屏（幂等；未创建返回 OK）。
     *
     * @return `OK` 或 `ERR:<异常类名>:<消息>`
     */
    String releaseHiddenDisplay();

    /**
     * 取隐藏虚拟屏最新帧（PNG 字节；无帧/编码失败返回 null）。
     * 帧只能由创建者进程的 ImageReader 获取（`screencap -d` 对非 owner 无效，PoC 实证）。
     *
     * @param maxWidth 预览最大宽度（0=原始尺寸；Watch 传 360 以降低 Binder/GC/编码压力）
     */
    byte[] captureHiddenFramePng(int maxWidth);

    /**
     * 2026-10-25（帧健康）：取隐藏虚拟屏最新帧的 **JPEG** 字节（自适应质量/尺寸）。
     *
     * 背景：PNG 全尺寸在图片/视频类界面极易超过 Binder 安全上限（900KB），原实现直接
     * 返回 null → 工具层表现为"暂无可用画面"。JPEG 体积通常小一个数量级，且可在超限时
     * **降质/降尺寸**而不是"没画面"。
     *
     * @param maxWidth 预览最大宽度（0=原始尺寸）
     * @param quality  初始 JPEG 质量（1~100；超限时服务端自行逐级下调）
     * @return JPEG 字节；无帧/编码失败返回 null
     */
    byte[] captureHiddenFrameJpeg(int maxWidth, int quality);

    /**
     * 2026-10-25（帧健康）：最新帧的元信息，用于判定"画面是否已冻结"。
     *
     * @return `OK:<width>:<height>:<ageMs>` 或 `ERR:<原因>`
     */
    String hiddenFrameInfo();

    /**
     * 2026-10-26（ADR-046 修订；PRISM US-3117）：**本用户服务进程自己的 PID**。
     *
     * 用途：清理"孤儿用户服务"时必须排除当前在用的这个进程。调用方无法从 Binder 代理
     * 反查对端 PID（`Binder.getCallingPid()` 只在服务端有效），若传 -1 当占位，
     * 清理逻辑会把"为读取 ps 而刚创建的本进程"当孤儿杀掉（真机复核发现的缺陷）。
     *
     * @return `Process.myPid()`（正整数）
     */
    int selfPid();

    /**
     * 2026-10-26（真机修复）：**过滤后的**通知摘要，只回传与 [packageName] 相关的行。
     *
     * **为什么不能在调用方过滤**：`dumpsys notification` 原始输出约 660KB，AIDL 以
     * `String`（UTF-16）回传会超过 Binder 单次事务上限（约 1MB）→ 抛异常 → 调用方拿到
     * null。真机实证：新消息探针每 90s 返回 null，导致「收到消息却不回复」。
     * 过滤在**用户服务进程内**完成，回传量降到几 KB。
     *
     * @param packageName 目标包名（仅用于文本过滤，不作 shell 参数，无注入面）
     * @return 匹配行（已去首尾空白、`\n` 连接）；无匹配或读取失败返回 null
     */
    String notificationSnapshotFor(String packageName);
}
