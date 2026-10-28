package com.totop.app.navigation

/**
 * 全局路由常量。
 *
 * 参数约定（因 minSdk 24，不使用路由级对象序列化，统一走字符串路径参数）：
 * - kpoint_detail 需要知识点受控 ID，形如 KP_MATH_0312
 */
object Routes {
    // 顶层导航（底栏可见，共 4 项：首页 / 拍题 / 陪伴 / 功能中心）

    /** 首次使用引导：仅在尚未看过引导时作为启动页。 */
    const val ONBOARDING = "onboarding"

    /** 首页：启动即进入（看过引导后）。 */
    const val HOME = "home"

    /** 拍题/对话主入口。 */
    const val SOLVE = "solve"

    /** 拍题并强制新开一条对话（陪伴作业的「问一下 AI」用它）。 */
    const val SOLVE_NEW = "solve_new"

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

    /** 陪伴作业模式（每道题先自己写五分钟）。 */
    const val COMPANION = "companion"

    // 设置的子页面：进入即是一个独立页，而不是长列表里的折叠面板

    /** 模型与连接（BYOK）。 */
    const val SETTINGS_MODEL = "settings_model"

    /** 教学偏好。 */
    const val SETTINGS_TEACHING = "settings_teaching"

    /** 存储与数据（缓存、备份、导出、清空）。 */
    const val SETTINGS_STORAGE = "settings_storage"

    /** 关于与帮助（重看引导、免责声明）。 */
    const val SETTINGS_ABOUT = "settings_about"

    // 带参数页面
    const val KPOINT_DETAIL_ARG = "kpointId"

    /** 错题回顾详情（只读回看，不进入复习流程）。 */
    const val MISTAKE_DETAIL_ARG = "mistakeId"
    const val MISTAKE_DETAIL = "mistake_detail/{$MISTAKE_DETAIL_ARG}"
    const val KPOINT_DETAIL = "kpoint_detail/{$KPOINT_DETAIL_ARG}"

    fun kpointDetail(kpointId: String): String = "kpoint_detail/" + kpointId

    fun mistakeDetail(mistakeId: String): String = "mistake_detail/" + mistakeId
}
