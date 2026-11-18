/**
 * To Top 领域层入口。
 *
 * 这一层只放纯函数与领域模型：掌握度、错因分类、题型判定、改写自洽校验。
 * **不许 import 任何 uni 或平台 API**，否则它就无法在 Node 里单测，
 * 这条约束与旧树「domain 不依赖 Android SDK」是同一条规矩换了个主语。
 *
 * T1 只建立骨架，领域代码从 T3 与 T6 开始往这里放。
 */
export const CORE_VERSION = "1.0.0";
