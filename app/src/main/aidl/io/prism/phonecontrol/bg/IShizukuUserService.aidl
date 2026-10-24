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
}
