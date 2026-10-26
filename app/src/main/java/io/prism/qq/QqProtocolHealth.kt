package io.prism.qq

import io.prism.qq.onebot.OneBotApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 协议端**账号在线态**探测（2026-10-26 新增；PRD US-3125）。
 *
 * **为什么要单独做**：WS 已连接 ≠ QQ 在线。真机取证：
 * `get_status` 返回独立的 `online` 字段，而账号态每 ~2 小时会失效一次
 * （NapCat 日志 `Login Error,ErrType:1 ErrCode:3`，随后需要扫码）。
 * 原实现只用 WS 连接态，于是设置页永远显示"已连接"，用户只感觉到"又不回了"。
 *
 * 三态：
 * - [State.ONLINE]：协议端可达且账号在线（可收发）；
 * - [State.QQ_OFFLINE]：协议端可达但账号离线（需在 PC 上重新登录）；
 * - [State.UNREACHABLE]：HTTP 不可达（进程没起/网络不通）。
 *
 * 只读、不修改任何协议端状态；失败不抛异常。
 */
class QqProtocolHealth(
    private val client: OneBotApi,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    enum class State { ONLINE, QQ_OFFLINE, UNREACHABLE }

    /** 一次探测结果。 */
    data class Status(
        val state: State,
        val selfId: Long = 0L,
        val nickname: String = "",
        val appVersion: String = "",
        val detail: String = "",
        val checkedAtMs: Long = 0L
    ) {
        val online: Boolean get() = state == State.ONLINE
    }

    /**
     * 探测一次（约 1~2 个 HTTP 往返；调用方应按 60s 级频率缓存，不要每次回复都探）。
     */
    suspend fun probe(): Status {
        val checkedAt = clock()
        val statusResponse = try {
            client.call("get_status", "{}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Status(
                state = State.UNREACHABLE,
                detail = "协议端不可达（${e::class.simpleName}）",
                checkedAtMs = checkedAt
            )
        }
        if (!statusResponse.ok) {
            return Status(
                state = State.UNREACHABLE,
                detail = statusResponse.message?.take(80) ?: "协议端未响应",
                checkedAtMs = checkedAt
            )
        }
        val data = statusResponse.data?.jsonObject
        val online = data?.get("online")?.jsonPrimitive?.booleanOrNull ?: false
        val good = data?.get("good")?.jsonPrimitive?.booleanOrNull ?: online
        if (!online) {
            return Status(
                state = State.QQ_OFFLINE,
                detail = "协议端在线但 QQ 账号已离线（需在 PC 上重新登录）",
                checkedAtMs = checkedAt
            )
        }
        // 在线时补一次登录信息（昵称/号），失败不影响在线判定
        val login = runCatching { client.call("get_login_info", "{}") }.getOrNull()
        val loginData = login?.data?.jsonObject
        val version = runCatching { client.call("get_version_info", "{}") }.getOrNull()
            ?.data?.jsonObject
        return Status(
            state = State.ONLINE,
            selfId = loginData?.get("user_id")?.jsonPrimitive?.longOrNull ?: 0L,
            nickname = loginData?.get("nickname")?.jsonPrimitive?.contentOrNull.orEmpty(),
            appVersion = version?.get("app_version")?.jsonPrimitive?.contentOrNull.orEmpty(),
            detail = if (good) "在线" else "在线（状态告警）",
            checkedAtMs = checkedAt
        )
    }
}
