package io.prism.network

import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 进程内 MCP Transport —— 用配对协程通道桥接同一进程内的 Client 与 Server 两端（ADR-006 5.2）。
 *
 * MCP SDK 的 [AbstractTransport] 已自动装配 `onMessage`/`onError`/`onClose`，且
 * `Protocol.connect` 会自动调用 `start()`；故本实现只需实现 [start]/[send]/[close]
 * 三个抽象方法完成消息搬运。
 *
 * 两端各持一条发送通道与一条接收通道，[createPair] 返回交叉连接的 Client 端与 Server 端：
 * - Client 端把消息写入 `clientToServer`，从 `serverToClient` 读取；
 * - Server 端把消息写入 `serverToClient`，从 `clientToServer` 读取。
 *
 * 通道采用 [Channel.UNLIMITED]（无界），避免 MCP 握手阶段（initialize / 通知并发）因背压死锁。
 * 两端共享同一 [CoroutineScope]，关闭任一端即取消整个桥接。
 */
class InProcessTransport private constructor(
    private val sendTo: Channel<JSONRPCMessage>,
    private val receiveFrom: Channel<JSONRPCMessage>,
    private val scope: CoroutineScope
) : AbstractTransport() {

    /**
     * 启动接收协程：从接收通道读取消息并派发给 [AbstractTransport.onMessage]。
     *
     * 通道关闭或协程取消时回调 [AbstractTransport.invokeOnCloseCallback] 通知对端断开。
     * 协程取消必须重新抛出（结构化并发，CR-01 对齐）。
     */
    override suspend fun start() {
        scope.launch {
            try {
                for (message in receiveFrom) {
                    _onMessage(message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _onError(e)
            } finally {
                invokeOnCloseCallback()
            }
        }
    }

    /** 将一条消息写入发送通道（由 [TransportSendOptions] 的空实现承接，进程内无需序列化）。 */
    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        sendTo.send(message)
    }

    /** 关闭桥接：取消接收协程并关闭两条通道。 */
    override suspend fun close() {
        scope.cancel()
        sendTo.close()
        receiveFrom.close()
    }

    companion object {
        /**
         * 创建一对交叉连接的进程内 Transport。
         *
         * @return 返回 pair，其 first 为 Client 端、second 为 Server 端。
         */
        fun createPair(): Pair<InProcessTransport, InProcessTransport> {
            val clientToServer = Channel<JSONRPCMessage>(Channel.UNLIMITED)
            val serverToClient = Channel<JSONRPCMessage>(Channel.UNLIMITED)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val clientEnd = InProcessTransport(
                sendTo = clientToServer,
                receiveFrom = serverToClient,
                scope = scope
            )
            val serverEnd = InProcessTransport(
                sendTo = serverToClient,
                receiveFrom = clientToServer,
                scope = scope
            )
            return clientEnd to serverEnd
        }
    }
}