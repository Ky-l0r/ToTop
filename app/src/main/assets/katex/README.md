# KaTeX 资源说明（assets/katex/）

## 现状

本目录当前**不包含** KaTeX 官方发行包（katex.min.js / katex.min.css / fonts/），
原因是本次构建环境完全离线，无法从 jsDelivr / npm 下载并校验哈希。

为了不让公式区块变成空白，这里提供了一份**自研**的轻量 LaTeX 子集渲染器作为兜底：

| 文件 | 归属 | 作用 |
| --- | --- | --- |
| totop-latex.min.js | 本项目自研（MIT） | 覆盖中学数学常见语法的极简 LaTeX 解析 + 渲染器 |
| totop-latex.min.css | 本项目自研（MIT） | 上述渲染器输出结构的样式 |
| （待补）katex.min.js | KaTeX 0.16.x（MIT） | 正式渲染器 |
| （待补）katex.min.css | KaTeX 0.16.x（MIT） | 正式样式 |
| （待补）fonts/*.woff2 | KaTeX 0.16.x（MIT） | 数学字体 |

**不是 MathJax**：本项目明确禁止引入 MathJax（体积大、启动慢、与离线打包冲突），
兜底渲染器也不依赖任何第三方库或 CDN。

## 宿主页如何选择渲染器

assets/formula_render.html 的脚本按以下顺序自动判定，Kotlin 侧无需关心：

1. assets/katex/katex.min.js 加载成功（`typeof katex.render === "function"`）-> 用 KaTeX；
2. KaTeX 不可用，或 KaTeX 对某条语法抛出异常 -> 用 totop-latex.min.js 重试；
3. 两者都不行 -> 回调 `onFailed`，Kotlin 侧由 FormulaText 降级为纯文本显示。

因此**补齐 KaTeX 资源后无需改动任何 Kotlin 代码**，兜底渲染器会自动退居二线。

## TODO：补齐 KaTeX 正式资源

在有外网的构建机上执行（版本建议与 promptVersion 一样固定并记录到 CHANGELOG）：

```bash
KATEX_VERSION=0.16.11
BASE="https://cdn.jsdelivr.net/npm/katex@${KATEX_VERSION}/dist"
curl -fL "${BASE}/katex.min.js"  -o app/src/main/assets/katex/katex.min.js
curl -fL "${BASE}/katex.min.css" -o app/src/main/assets/katex/katex.min.css
# 字体：只保留 woff2（Android WebView 4.4+ 全部支持 woff2，去掉 ttf/woff 可省约 700KB）
curl -fL "${BASE}/fonts.zip" -o /tmp/katex-fonts.zip
unzip -j /tmp/katex-fonts.zip '*.woff2' -d app/src/main/assets/katex/fonts/
```

补齐后必须做的三件事：

1. 校验完整性：确认 `katex.min.css` 中 `url(fonts/...)` 的相对路径与 `assets/katex/fonts/` 下的文件名一致
   （KaTeX 的 CSS 使用相对 `fonts/` 前缀，与本目录结构天然对齐）；
2. 记录来源与许可证：在 THIRD_PARTY.md 追加 KaTeX（MIT）条目与版本号、下载日期、sha256；
3. 包体核对：woff2 全集约 350KB，加上 js/css 约 1.2MB，仍在 30MB 包体门禁内；
   若需进一步压缩，可只保留 Mathematical-Italic / Main-Regular / Size1-4-Regular 等实际用到的字体。

## 为什么不用 MathJax

- 体积：MathJax 3 的 tex-svg 完整包压缩后仍 >1MB，且需要额外的组件加载逻辑；
- 启动：WebView 中的 MathJax 首次排版有明显延迟（数百毫秒），与 300ms 渲染预算冲突；
- 团队约束：《AI 辅助学习 App 开发方案》明确要求本地 KaTeX。

## 兜底渲染器的能力边界

支持：分组 `{}`、分式 `\frac \dfrac \tfrac`、根号 `\sqrt` 与 `\sqrt[n]{}`、
上下标 `_ ^`、`\text \mathrm \mathbf`、`\left( ... \right)`、
希腊字母与常用符号命令（`\times \leq \angle \triangle \pi \theta` 等）、
函数名（`\sin \cos \log \ln \lim \sum \int`）、空白命令 `\, \; \quad`、
`\overline \vec`。

不支持：矩阵、`aligned` / `cases` 多行对齐（会被渲染为单行文本片段）、
`\overbrace` 等装饰、颜色与字号命令。这些场景在 KaTeX 补齐后自动获得完整支持。
遇到无法识别的命令时按原文输出，不抛异常、不出现空白块。
