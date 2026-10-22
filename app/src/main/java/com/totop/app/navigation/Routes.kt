package com.totop.app.navigation

/**
 * 全局路由常量。
 *
 * 参数约定（因 minSdk 24，不使用路由级对象序列化，统一走字符串路径参数）：
 * - kpoint_detail 需要知识点受控 ID，形如 KP_MATH_0312
 */
object Routes {
    // 顶层导航（底部栏可见，最多 3 项）

    /** 首页：启动即进入。 */
    const val HOME = "home"

    /** 拍题/对话主入口。 */
    const val SOLVE = "solve"

    /** 功能中心（原错题/知识点/学情/设置的聚合入口）。 */
    const val HUB = "hub"

    /** 考前针对性复习资料。 */
    const val REVIEW_MATERIAL = "review_material"

    /** 离线记题列表。 */
    const val OFFLINE = "offline"
    const val MISTAKES = "mistakes"
    const val KNOWLEDGE = "knowledge"
    const val REPORT = "report"
    const val SETTINGS = "settings"

    // 二级页面（底部栏隐藏）
    const val REVIEW = "review"
    const val BACKUP = "backup"

    // 带参数页面
    const val KPOINT_DETAIL_ARG = "kpointId"

    /** 错题回顾详情（只读回看，不进入复习流程）。 */
    const val MISTAKE_DETAIL_ARG = "mistakeId"
    const val MISTAKE_DETAIL = "mistake_detail/{$MISTAKE_DETAIL_ARG}"
    const val KPOINT_DETAIL = "kpoint_detail/{$KPOINT_DETAIL_ARG}"

    fun kpointDetail(kpointId: String): String = "kpoint_detail/" + kpointId

    fun mistakeDetail(mistakeId: String): String = "mistake_detail/" + mistakeId
}
