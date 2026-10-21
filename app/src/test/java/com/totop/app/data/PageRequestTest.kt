package com.totop.app.data

import com.totop.app.data.repo.PageRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 分页参数（LIMIT/OFFSET 方案）的 JVM 单测。 */
class PageRequestTest {
    @Test
    @DisplayName("默认第一页 size=20、offset=0")
    fun defaults() {
        val request = PageRequest()
        assertEquals(0, request.page)
        assertEquals(PageRequest.DEFAULT_SIZE, request.size)
        assertEquals(0, request.offset)
    }

    @Test
    @DisplayName("offset = page * size")
    fun offsetCalculation() {
        assertEquals(40, PageRequest(page = 2, size = 20).offset)
        assertEquals(90, PageRequest(page = 3, size = 30).offset)
    }

    @Test
    @DisplayName("next() 递增页码并保持 size")
    fun nextPage() {
        val next = PageRequest(page = 1, size = 10).next()
        assertEquals(2, next.page)
        assertEquals(10, next.size)
        assertEquals(20, next.offset)
    }

    @Test
    @DisplayName("非法页码与非法条数被拒绝")
    fun invalidArguments() {
        assertThrows<IllegalArgumentException> { PageRequest(page = -1) }
        assertThrows<IllegalArgumentException> { PageRequest(size = 0) }
        assertThrows<IllegalArgumentException> { PageRequest(size = PageRequest.MAX_SIZE + 1) }
    }

    @Test
    @DisplayName("单页上限为 100")
    fun maxSize() {
        assertEquals(100, PageRequest.MAX_SIZE)
        assertEquals(100, PageRequest(size = 100).size)
    }
}
