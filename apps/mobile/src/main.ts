import { createPinia } from "pinia";
import { createSSRApp } from "vue";
import App from "./App.vue";

export function createApp() {
  const app = createSSRApp(App);
  // Pinia 是 uni-app 官方支持的状态方案；guide 13.2 规定"一页一个 store"。
  app.use(createPinia());
  return {
    app,
  };
}
