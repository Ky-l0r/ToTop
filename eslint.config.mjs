import js from "@eslint/js";
import pluginVue from "eslint-plugin-vue";
import globals from "globals";
import tseslint from "typescript-eslint";

/**
 * 全仓 lint 规则（ESLint 10 flat config）。
 *
 * 三条底线：
 * 1. **禁止 any**。模型返回的 JSON 必须过运行时校验，用 any 断言等于把校验关掉。
 * 2. Vue 用 essential 档：只看会不会出错，不介入排版偏好（本项目不引 Prettier）。
 * 3. 冻结的原生树（app/、legacy/）不在这里管，它们走 ktlint / detekt。
 */
export default tseslint.config(
  {
    ignores: [
      "**/node_modules/**",
      "**/dist/**",
      "**/unpackage/**",
      "app/**",
      "legacy/**",
      "**/*.d.ts",
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs["flat/essential"],
  {
    files: ["**/*.vue"],
    languageOptions: {
      parserOptions: {
        // .vue 里写的是 TS，需要把 script 块交给 TS 解析器。
        parser: tseslint.parser,
        ecmaVersion: "latest",
        sourceType: "module",
      },
    },
  },
  {
    // 页面文件名是 uni-app 的约定（一页一目录，入口固定叫 index.vue），
    // 这条规则针对的是"组件"，套到页面上只是噪音。组件目录仍然受它约束。
    files: ["apps/mobile/src/pages/**/*.vue"],
    rules: {
      "vue/multi-word-component-names": "off",
    },
  },
  {
    languageOptions: {
      globals: {
        ...globals.browser,
        // uni-app 注入的全局对象，声明出来免得被 no-undef 误报。
        uni: "readonly",
        plus: "readonly",
        getCurrentPages: "readonly",
      },
    },
    rules: {
      "@typescript-eslint/no-explicit-any": "error",
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
      "no-console": ["warn", { allow: ["warn", "error"] }],
      "no-debugger": "error",
      eqeqeq: ["error", "smart"],
    },
  },
);
