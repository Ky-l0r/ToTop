package com.totop.app.data.remote

import java.util.UUID

/**
 * 客户端幂等请求 ID（开发方案 12.1 第 4 条：任何网络调用必须带 client_request_id）。
 *
 * 用途：
 * 1. 写入 ai_trace.requestId，把同一次用户操作的 A/B/C/D 四阶段调用串成一条链路；
 * 2. [IdempotencyGuard] 据此在退避重试时复用同一个 ID，避免厂商侧重复计费；
 * 3. 用户点「重试」时由 UI 生成新的 requestId，从而产生新的 trace 链路。
 */
object RequestId {
    /** 生成新的请求 ID（UUID v4，小写带连字符）。 */
    fun newId(): String = UUID.randomUUID().toString()

    /**
     * 由稳定输入派生请求 ID（同一输入得到同一 ID）。
     * 用于「同一次解题重复进入页面」的幂等场景；不得用于需要区分用户操作的场景。
     */
    fun derive(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()

    /** 基础格式校验：非空且长度不小于 8。 */
    fun isValid(candidate: String?): Boolean = !candidate.isNullOrBlank() && candidate.length >= 8
}

/**
 * 幂等去重守卫。
 *
 * 语义（与开发方案 3.4「同一 sha256 二次打开 -> 本地缓存，不调用」一致）：
 * - [begin] 成功登记后，同一 requestId 的重复进入返回 false；
 * - [complete] 完成后从内存表移除，允许后续生成新的 requestId 再次调用；
 * - 仅做内存登记，进程重启后由 cache_entry 兜底。
 *
 * 线程安全：内部使用同步块，调用频率为「每次解题一次」，无性能风险。
 */
class IdempotencyGuard {
    private val inFlight = HashSet<String>()
    private val completed = LinkedHashSet<String>()

    /** 尝试开始一次请求；已在执行或已完成返回 false。 */
    @Synchronized
    fun begin(requestId: String): Boolean {
        if (requestId.isBlank()) return false
        if (inFlight.contains(requestId)) return false
        if (completed.contains(requestId)) return false
        inFlight.add(requestId)
        return true
    }

    /** 标记请求完成（成功或失败均可），从执行中集合移除并记入已完成集合。 */
    @Synchronized
    fun complete(requestId: String) {
        inFlight.remove(requestId)
        completed.add(requestId)
        while (completed.size > MAX_COMPLETED) {
            val oldest = completed.first()
            completed.remove(oldest)
        }
    }

    /** 请求被取消/异常退出，仅从执行中集合移除，允许重来。 */
    @Synchronized
    fun abort(requestId: String) {
        inFlight.remove(requestId)
    }

    @Synchronized
    fun isInFlight(requestId: String): Boolean = inFlight.contains(requestId)

    @Synchronized
    fun clear() {
        inFlight.clear()
        completed.clear()
    }

    private companion object {
        /** 已完成 ID 的保留上限，防止长时间使用后内存增长。 */
        const val MAX_COMPLETED: Int = 256
    }
}
