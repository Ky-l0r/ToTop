/** 图表组件专用类型（纯前端 UI 类型，与后端契约无关）。 */

export interface ChartSeries {
  /** 图例名称，同时作为隐藏状态的 key（需唯一） */
  name: string
  color: string
  /** 与 labels 等长，null 表示该次考试缺考/无数据（折线在此断开） */
  data: (number | null)[]
  /** 线宽，默认 2；总分线建议 3 */
  lineWidth?: number
}
