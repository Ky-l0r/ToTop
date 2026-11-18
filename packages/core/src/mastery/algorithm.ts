/**
 * 掌握度算法。
 *
 * 1.0 只保留一个数：0 到 1 的掌握度，展示在知识点页。0.8.0 那套雷达、折线、饼图全删了——
 * 它们回答的是"我最近学得怎么样"，而学生真正要的是"哪块不熟，先攻它"。
 *
 * 三条设计取舍：
 * 1. **越接近两端，单次变化越小**。已经 0.9 的知识点做对一道题不该跳到 0.95，
 *    刚 0.1 的知识点做错一道也不该直接归零——这两种跳变都会让学生不再相信这个数。
 * 2. **做错比做对影响更大**。做对可能是蒙的，做错是实打实的。
 * 3. **不做时间衰减**。看起来高级，实际表现是"我什么都没做，分数怎么掉了"，
 *    解释成本远大于收益。要衰减等有复习行为数据之后再说。
 */

export type MasteryEventKind =
  /** 巩固练习点了"做对了"。 */
  | "correct"
  /** 巩固练习点了"仍不懂"。 */
  | "wrong"
  /** 新错题落库（做错一次是比"仍不懂"更弱的信号：可能只是算错）。 */
  | "mistake";

/** 新知识点的起点。0.5 表示"没数据"，既不奖励也不惩罚。 */
export const INITIAL_MASTERY = 0.5;

/**
 * 绿 / 黄 / 红三档的分界（百分比）。
 * 与指南 5.7 的章节热力一致：红 <40，黄 40-70，绿 >70。
 */
export const HEAT_THRESHOLDS = { red: 40, green: 70 } as const;

export type HeatLevel = "red" | "yellow" | "green";

export function clampScore(score: number): number {
  if (!Number.isFinite(score)) return INITIAL_MASTERY;
  return Math.max(0, Math.min(1, score));
}

/**
 * 施加一次事件后的新掌握度。
 *
 * 增益与损失都按"距离目标端的剩余空间"缩放：
 * - 做对：`(1 - score) * 0.30`
 * - 仍不懂：`score * 0.35`（比做对的权重大）
 * - 新错题：`score * 0.20`
 */
export function applyMasteryEvent(score: number, kind: MasteryEventKind): number {
  const current = clampScore(score);
  switch (kind) {
    case "correct":
      return clampScore(current + (1 - current) * 0.3);
    case "wrong":
      return clampScore(current - current * 0.35);
    case "mistake":
      return clampScore(current - current * 0.2);
    default:
      return current;
  }
}

/** 章节热力：由 0 到 1 的分数给出红 / 黄 / 绿。 */
export function heatOf(score: number): HeatLevel {
  const percent = clampScore(score) * 100;
  if (percent < HEAT_THRESHOLDS.red) return "red";
  if (percent > HEAT_THRESHOLDS.green) return "green";
  return "yellow";
}

/** 掌握度区间筛选用的档位。 */
export type MasteryBand = "all" | "weak" | "mid" | "strong";

export function bandOf(score: number): Exclude<MasteryBand, "all"> {
  const heat = heatOf(score);
  if (heat === "red") return "weak";
  if (heat === "green") return "strong";
  return "mid";
}

/** 一批知识点的已掌握比例，用于章节汇总（避免在页面里重复算）。 */
export function masteredRatio(scores: readonly number[]): number {
  if (scores.length === 0) return 0;
  const mastered = scores.filter((score) => clampScore(score) >= 0.6).length;
  return mastered / scores.length;
}
