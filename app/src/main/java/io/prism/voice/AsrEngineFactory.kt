package io.prism.voice

import android.content.Context
import android.util.Log

/**
 * 批次22（SZ-201，ADR-047）：ASR 引擎工厂。
 *
 * 优先 **SherpaAsrEngine**（端侧流式，V2.1 贾维斯形态——位于 sherpa 条件源集，
 * AAR/模型就位后参与编译）；经反射创建避免主源集编译依赖。任何失败回落
 * [SystemAsrEngine]（按住说话降级模式，PRD SZ-201）。
 */
object AsrEngineFactory {

    private const val TAG = "AsrEngineFactory"

    fun create(context: Context): AsrEngine {
        return try {
            val engine = Class.forName("io.prism.voice.SherpaAsrEngine")
                .getConstructor(Context::class.java)
                .newInstance(context) as AsrEngine
            Log.i(TAG, "使用端侧流式引擎（SherpaAsrEngine）")
            engine
        } catch (e: Throwable) {
            // ClassNotFoundException（AAR/源集缺失）｜NoClassDefFoundError（AAR 在但 sherpa native 缺失）
            Log.i(TAG, "端侧引擎不可用（${e::class.simpleName}），回落系统 SpeechRecognizer（按住说话）")
            SystemAsrEngine(context.applicationContext)
        }
    }
}
