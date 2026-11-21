package io.prism.ui.settings

/**
 * 设置的 6 个任务入口（ADR-079 D2）。
 *
 * 存在的意义：设置项已累积到 47 行，平铺时用户只能"扫"而不能"找"。按用户要做的事分组后，
 * 首屏只回答「我要改哪一类」，具体项放进详情页渐进披露。
 *
 * **不变量**：47 个设置项一个不删，全部落在某个 [SettingsSection] 下
 * （`SettingInventoryTest` 用清单断言，见 ADR-079 D6）。
 */
enum class SettingsSection(val title: String) {
    /** Provider 列表/新建/编辑、API Key、深度思考与强度。 */
    MODEL("模型与密钥"),

    /** 用户规则、识图与视觉旁路。 */
    BEHAVIOR("助手行为"),

    /** 无障碍、自愈、高危拦截、执行面、Watch、Shizuku、工具权限、图片消息。 */
    PHONE("手机操控"),

    /** QQ 小号：总开关、协议端管家、连接、定时、自动回复、表情库。 */
    QQ("QQ 小号"),

    /** 语音引擎、热词、角色包、桌宠。 */
    VOICE("语音与形象"),

    /** 搜索增强、WebView 抓取、性能档位、关于。 */
    SYSTEM("网络、设备与关于");

    companion object {
        fun fromName(name: String?): SettingsSection? =
            entries.firstOrNull { it.name == name }
    }
}
