import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";
import { fileURLToPath, URL } from "node:url";

// 只保留一个插件：uni 官方插件负责把 Vue 单文件组件按平台编译。
// 别名 @ 指向 src，页面里引用组件与工具时不写一长串相对路径，搬文件时不用改 import。
export default defineConfig({
  plugins: [uni()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
      // 领域层直接指到源码：它没有构建产物，也不该有（端上与测试共用同一份 TS）。
      "@totop/core": fileURLToPath(new URL("../../packages/core/src/index.ts", import.meta.url)),
    },
  },
});
