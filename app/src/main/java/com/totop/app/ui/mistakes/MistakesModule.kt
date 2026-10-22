package com.totop.app.ui.mistakes

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 错题本页的依赖装配（Hilt）。
 *
 * 只绑定「ui 层新增的只读查询端口」，不改动 data 层既有定义：
 * 错题本完全离线，ViewModel 拿到的只有 [MistakeBrowseGateway] 与既有 Repository，
 * 看不到 Room、SQL 或任何网络客户端（开发方案「ViewModel 不直接调用 Retrofit」）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MistakesModule {
    /** 分页查询端口的实现绑定。 */
    @Binds
    @Singleton
    abstract fun bindMistakeBrowseGateway(impl: RoomMistakeBrowseGateway): MistakeBrowseGateway

    /**
     * 错题回顾详情端口的实现绑定。
     *
     * 与分页端口分开：详情要一次 JOIN 取回 question 与 mistake_record 的完整快照
     * （含原图路径、学生步骤、标准解答 JSON 列），与列表的投影字段完全不同，
     * 塞进同一个接口会让两边都出现大量用不上的字段。
     */
    @Binds
    @Singleton
    abstract fun bindMistakeDetailGateway(impl: RoomMistakeDetailGateway): MistakeDetailGateway
}
