import fs from "node:fs";
import path from "node:path";

/**
 * 页面样式门禁。
 *
 * 挡的是这三类东西：
 *   1. 十六进制色值 —— 暗色模式下不会跟着变，等于在暗色里留一块亮斑；
 *   2. 裸字号 / 裸圆角 / 裸间距 —— 0.8.0 的页面上同时存在 3/5/7/9/13/14/22 这些数，
 *      看着像随手敲的，而且改一处大小要翻十个文件；
 *   3. 裸行高 —— 同上。
 *
 * 只扫 pages 与 App.vue：tokens.css 自己当然要写裸值。
 */
const ROOTS = ["src/pages", "src/App.vue"];

const RULES = [
  { name: "硬编码色值", pattern: /#[0-9a-fA-F]{3,8}\b/, hint: "改用 var(--tt-primary) 这类语义色" },
  { name: "裸字号", pattern: /font-size:\s*\d+px/, hint: "改用 var(--tt-text-N)" },
  { name: "裸圆角", pattern: /border-radius:\s*\d+px/, hint: "改用 var(--tt-radius-sm/md/lg/pill)" },
  {
    name: "裸间距",
    pattern: /(padding|margin|gap|row-gap|column-gap):\s*[^;]*\d+px/,
    hint: "改用 var(--tt-space-N)",
  },
  { name: "裸行高", pattern: /line-height:\s*1\.\d+/, hint: "改用 var(--tt-leading-*)" },
];

function collect(target) {
  const stat = fs.statSync(target);
  if (!stat.isDirectory()) return [target];
  const out = [];
  for (const entry of fs.readdirSync(target, { withFileTypes: true })) {
    const p = path.join(target, entry.name);
    if (entry.isDirectory()) out.push(...collect(p));
    else if (p.endsWith(".vue") || p.endsWith(".css")) out.push(p);
  }
  return out;
}

const problems = [];
for (const root of ROOTS) {
  const full = path.resolve(process.cwd(), root);
  if (!fs.existsSync(full)) continue;
  for (const file of collect(full)) {
    const lines = fs.readFileSync(file, "utf8").split("\n");
    lines.forEach((line, index) => {
      // 变量定义行（--tt-x: #fff）在 tokens.css 里，这里不该出现；真出现也算违规
      for (const rule of RULES) {
        if (rule.pattern.test(line)) {
          problems.push(`${file}:${index + 1}  ${rule.name} → ${rule.hint}\n    ${line.trim()}`);
        }
      }
    });
  }
}

if (problems.length > 0) {
  console.error("样式里出现了不该有的裸值：\n" + problems.join("\n"));
  process.exit(1);
}

console.log("样式门禁通过：页面里没有裸色值 / 字号 / 圆角 / 间距 / 行高");
