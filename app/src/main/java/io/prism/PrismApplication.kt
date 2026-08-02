package io.prism

import android.app.Application
import io.objectbox.BoxStore
import io.prism.data.MyObjectBox

/**
 * Prism 应用入口 —— 初始化 ObjectBox 数据库。
 *
 * 在 [onCreate] 中构建 [BoxStore] 单例，供全应用持久化模块使用
 * （知识库 / 记忆系统 / Provider 配置等）。后续可通过 Hilt 注入或 Application cast 访问。
 */
class PrismApplication : Application() {

    lateinit var boxStore: BoxStore
        private set

    override fun onCreate() {
        super.onCreate()
        boxStore = MyObjectBox.builder()
            .androidContext(this)
            .build()
    }
}
