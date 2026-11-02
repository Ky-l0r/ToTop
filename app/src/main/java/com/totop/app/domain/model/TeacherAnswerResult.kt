package com.totop.app.domain.model

/**
 * 写入「老师给的答案」的结果。
 *
 * 为什么区分 [Written] 与 [WrittenDisputed]：两者给学生的话完全不同——
 * 一致时说「已按老师的答案更新」就够了；不一致时必须说清「你的答案已按老师为准，
 * 但这道题被标成了存疑」，否则学生打开错题本会看到一条和 AI 讲的不一样的记录却不知道为什么。
 */
enum class TeacherAnswerResult {
    /** 已写入，且与 AI 原结论一致。 */
    Written,

    /** 已写入，但与 AI 原结论不一致，这道题已标记为「存疑」。 */
    WrittenDisputed,

    /** 这道题在库里找不到（已被删除）。 */
    NotFound,

    /** 答案为空，什么都没做。 */
    Ignored,
}
