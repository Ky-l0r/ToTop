package com.totop.app.domain.usecase.port

import java.util.UUID

/*
 * 领域层端口：与外部基础设施无关的极小能力（时钟、ID、配置读取）。
 *
 * 全部为纯 Kotlin 接口，可注入固定实现，保证 UseCase 在单测中完全可复现：
 * 同一组输入 + 同一个固定时钟 = 同一份输出。
 */

/** UTC 时钟。所有时间戳为 UTC 毫秒 Long（开发方案 5.2）。 */
fun interface DomainClock {
    fun nowUtcMillis(): Long

    companion object {
        /** 系统时钟（唯一允许读系统时间的地方，便于单测替换）。 */
        val SYSTEM: DomainClock = DomainClock { System.currentTimeMillis() }

        /** 固定时钟。 */
        fun fixed(millis: Long): DomainClock = DomainClock { millis }
    }
}

/** 一天的毫秒数，UTC 日序号换算用。 */
const val DAY_MILLIS: Long = 86_400_000L

/** UTC 毫秒 -> UTC 日序号（自 1970-01-01 起的天数，与时区无关）。 */
fun epochDayOf(utcMillis: Long): Long = Math.floorDiv(utcMillis, DAY_MILLIS)

/** UTC 日序号 -> 当天 00:00:00 UTC 的毫秒。 */
fun startOfUtcDay(utcMillis: Long): Long = epochDayOf(utcMillis) * DAY_MILLIS

/** 幂等 ID 生成。 */
fun interface IdGenerator {
    /** 生成一个新的唯一 ID（用于 requestId、题目 ID、错题 ID）。 */
    fun newId(): String

    companion object {
        /** 默认实现：UUID v4。不依赖 Android，可在 JVM 使用。 */
        val UUID_GENERATOR: IdGenerator = IdGenerator { UUID.randomUUID().toString() }

        /** 递增序列（单测用，保证断言稳定）。 */
        fun sequence(prefix: String = "id"): IdGenerator {
            val counter = java.util.concurrent.atomic.AtomicLong(0)
            return IdGenerator { prefix + "-" + counter.incrementAndGet() }
        }
    }
}

/**
 * 应用设置的只读视图（UseCase 需要的少量开关）。
 *
 * 刻意做成窄接口而不是 `Map<String, String>`：
 * 这样「周报 AI 解读」等开关的默认值集中在一处，不会散落在各个 UseCase 里。
 */
interface DomainSettings {
    /** 是否允许调用 AI 生成周报解读（关闭时输出纯统计版，FR-5.4）。 */
    suspend fun reportAiEnabled(): Boolean

    /** 是否允许 AI 出题（关闭时不消耗 token，FR-9.4）。 */
    suspend fun exerciseAiEnabled(): Boolean

    /** 「简洁 / 详细」两档：详细档使用更大的 max_tokens。 */
    suspend fun detailedOutput(): Boolean

    /** 当前用户是否已配置可用的 API Key（决定是否进入本地模式，FR-6.5）。 */
    suspend fun llmConfigured(): Boolean

    companion object {
        /** 默认设置：AI 解读开、AI 出题开、简洁档、Key 已配置（仅用于单测与预览）。 */
        val DEFAULTS: DomainSettings =
            object : DomainSettings {
                override suspend fun reportAiEnabled(): Boolean = true

                override suspend fun exerciseAiEnabled(): Boolean = true

                override suspend fun detailedOutput(): Boolean = false

                override suspend fun llmConfigured(): Boolean = true
            }

        /** 纯本地模式：无 Key，AI 能力全部关闭。 */
        val LOCAL_ONLY: DomainSettings =
            object : DomainSettings {
                override suspend fun reportAiEnabled(): Boolean = false

                override suspend fun exerciseAiEnabled(): Boolean = false

                override suspend fun detailedOutput(): Boolean = false

                override suspend fun llmConfigured(): Boolean = false
            }
    }
}
