package com.totop.app.data.repo

/**
 * 分页请求。
 *
 * 全库统一选择 **LIMIT/OFFSET 分页**，不引入 Paging3，理由：
 * 1. 数据规模是个体学生的错题本（千级以内），OFFSET 的扫描成本可忽略；
 * 2. Paging3 需要额外的 paging-runtime + paging-compose 依赖与一整套 PagingSource 样板，
 *    与方案第 7 章 30MB 包体门禁和「不使用 XML View、少依赖」的工程约定冲突；
 * 3. LIMIT/OFFSET 可以同时提供 suspend 快照查询与 Flow 观察两种形态，离线导出、备份、
 *    instrumentation 测试都能直接复用同一套 DAO 方法。
 *
 * [page] 从 0 开始；[size] 默认 20（错题本一屏量）。
 */
data class PageRequest(
    val page: Int = 0,
    val size: Int = DEFAULT_SIZE,
) {
    init {
        require(page >= 0) { "页码不得为负：$page" }
        require(size in 1..MAX_SIZE) { "每页条数必须在 1..$MAX_SIZE：$size" }
    }

    /** 传给 DAO 的 OFFSET。 */
    val offset: Int get() = page * size

    /** 下一页。 */
    fun next(): PageRequest = copy(page = page + 1)

    companion object {
        /** 默认每页条数。 */
        const val DEFAULT_SIZE: Int = 20

        /** 单页上限，防止误传巨大值导致一次查询过多行。 */
        const val MAX_SIZE: Int = 100

        val FIRST: PageRequest = PageRequest()
    }
}
