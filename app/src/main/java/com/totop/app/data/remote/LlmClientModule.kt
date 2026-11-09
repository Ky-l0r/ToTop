package com.totop.app.data.remote

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * LLM 客户端的进程级绑定。
 *
 * 全应用唯一的 `LlmClient` 绑定原来挂在旧学情报告页的模块里；旧页删除后，
 * 它的消费者（设置页连通性校验、练习网关、学情 AI 解读）没有一个是「报告页专属」的，
 * 因此把绑定下沉到 remote 层，归属才正确——任何需要调模型的页面都能注入，
 * 而不必依赖某个 UI 模块恰好还存在。
 */
@Module
@InstallIn(SingletonComponent::class)
object LlmClientModule {
    /** 进程内单例：内部已持有独立连接池、超时与错误分类，UI 层看不到 OkHttp。 */
    @Provides
    @Singleton
    fun provideLlmClient(): LlmClient = LlmClient()
}
