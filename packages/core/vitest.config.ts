import { defineConfig } from "vitest/config";

/**
 * 测试跑在真 SQLite 与真 HTTP 服务上，单个文件的开销比纯函数测试大得多。
 * 按文件并发跑时，14 个文件同时起进程、同时开监听端口，本机出现过一次整体失败
 * （单独跑、重跑都全绿），所以改成按文件串行：多花几秒，换门禁不飘。
 */
export default defineConfig({
  test: {
    fileParallelism: false,
  },
});
