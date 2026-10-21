package com.totop.app.domain.model

/** 题目来源（开发方案 5.1 question.sourceType：拍照 / 相册 / 手输）。 */
enum class QuestionSourceType(val wire: String, val label: String) {
    CAMERA("camera", "拍照"),
    GALLERY("gallery", "相册"),
    MANUAL("manual", "手输"),
    ;

    companion object {
        val DEFAULT: QuestionSourceType = MANUAL

        fun fromWire(raw: String?): QuestionSourceType {
            val token = raw?.trim()?.lowercase().orEmpty()
            return values().firstOrNull { it.wire == token } ?: DEFAULT
        }
    }
}

/** 学生步骤状态，与 AI JSON 契约 `correct|wrong|missing` 对齐。 */
enum class StudentStepStatus(val wire: String, val label: String) {
    CORRECT("correct", "正确"),
    WRONG("wrong", "错误"),
    MISSING("missing", "缺失"),
    ;

    companion object {
        /** 兜底状态：无法识别时按「缺失」处理，避免把不可解析的步骤当作有效推导。 */
        val FALLBACK: StudentStepStatus = MISSING

        fun fromWire(raw: String?): StudentStepStatus {
            val token = raw?.trim()?.lowercase().orEmpty()
            return values().firstOrNull { it.wire == token } ?: FALLBACK
        }

        fun fromWireOrNull(raw: String?): StudentStepStatus? {
            val token = raw?.trim()?.lowercase().orEmpty()
            return values().firstOrNull { it.wire == token }
        }
    }
}

/** 知识点边类型，与开发方案 FR-7.1 一致。 */
enum class EdgeType(val wire: String, val label: String) {
    PREREQUISITE("prerequisite", "前置"),
    RELATED("related", "相关"),
    EXTENDS("extends", "延伸"),
    ;

    companion object {
        fun fromWire(raw: String?): EdgeType? {
            val token = raw?.trim()?.lowercase().orEmpty()
            return values().firstOrNull { it.wire == token }
        }
    }
}

/** AI 调用阶段（开发方案 5.1 ai_trace.stage：A/B/C/D）。 */
enum class AiStage(val wire: String, val label: String) {
    A("A", "结构化识题"),
    B("B", "独立求解"),
    C("C", "错因定位"),
    D("D", "知识点抽取"),
    ;

    companion object {
        fun fromWire(raw: String?): AiStage? {
            val token = raw?.trim()?.uppercase().orEmpty()
            return values().firstOrNull { it.wire == token }
        }
    }
}

/** 练习难度三档（开发方案 FR-9.1「难度分三档」）。 */
enum class ExerciseDifficulty(val wire: String, val label: String) {
    EASY("easy", "基础"),
    MEDIUM("medium", "进阶"),
    HARD("hard", "挑战"),
    ;

    companion object {
        fun fromWire(raw: String?): ExerciseDifficulty? {
            val token = raw?.trim()?.lowercase().orEmpty()
            return values().firstOrNull { it.wire == token }
        }
    }
}

/**
 * 复习自评结果（开发方案 FR-4.2：重做后由学生自己勾选「做对了 / 还是不会」，不使用机器打分）。
 *
 * score 用于掌握度算法：自评「会」记 +1，自评「还是不会」记 -1。
 */
enum class RecallSelfResult(val wire: String, val label: String, val score: Double) {
    GOT_IT("got_it", "做对了", 1.0),
    STILL_LOST("still_lost", "还是不会", -1.0),
    ;

    companion object {
        fun fromWire(raw: String?): RecallSelfResult? {
            val token = raw?.trim()?.lowercase().orEmpty()
            return values().firstOrNull { it.wire == token }
        }
    }
}

/** 掌握度色阶（开发方案 4.3.1：红 <40 / 黄 40-70 / 绿 >70）。 */
enum class MasteryBand(val label: String) {
    RED("红"),
    YELLOW("黄"),
    GREEN("绿"),
}
