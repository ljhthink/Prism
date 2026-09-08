package io.prism.voice

import android.content.Context
import android.util.Log

/**
 * 批次22（SZ-201，ADR-047）：ASR 引擎工厂。
 *
 * 优先 **SherpaAsrEngine**（端侧流式，V2.1 贾维斯形态——位于 sherpa 条件源集，
 * AAR/模型就位后参与编译）；经反射创建避免主源集编译依赖。任何失败回落
 * [SystemAsrEngine]（按住说话降级模式，PRD SZ-201）。
 *
 * 批次23-A（ADR-048）：新增**云端 Key 就绪分支**——[dashScopeKey] 非空（调用方经
 * ApiKeyRepository 解密传入，键名 [io.prism.voice.cloud.AliCloudAsrEngine.KEY_REF]）时
 * 创建 [io.prism.voice.cloud.AliCloudAsrEngine]（百炼 Paraformer-realtime-v2，流式常驻）。
 * **Key 未配置（null/blank）时云端不可选**，既有端侧/系统降级链零改动；未经用户手选不自动
 * 抢占端侧引擎（ADR-048 决策 3：双轨降级链=用户手选→探测失败回端侧，手选 UI 属后续批次）。
 */
object AsrEngineFactory {

    private const val TAG = "AsrEngineFactory"

    fun create(context: Context, dashScopeKey: String? = null): AsrEngine {
        // 批次23-A（ADR-048）：云端分支——Key 就绪才可选；创建失败（理论不可达）继续降级
        if (!dashScopeKey.isNullOrBlank()) {
            val cloud = runCatching {
                io.prism.voice.cloud.AliCloudAsrEngine(context.applicationContext, dashScopeKey)
            }
            cloud.onSuccess {
                Log.i(TAG, "使用云端流式引擎（AliCloudAsrEngine，Paraformer-realtime-v2）")
                return it
            }
            cloud.onFailure { e ->
                Log.w(TAG, "云端引擎创建失败（${e::class.simpleName}），回落端侧/系统链")
            }
        }
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
