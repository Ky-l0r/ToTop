/// <reference types="vite/client" />

declare module "*.vue" {
  import { DefineComponent } from "vue";
  // eslint-disable-next-line @typescript-eslint/no-explicit-any, @typescript-eslint/ban-types
  const component: DefineComponent<{}, {}, any>;
  export default component;
}

// 提示词以纯文本方式打进包：它们是数据，不属于代码，改文案不该动构建配置。
declare module "*?raw" {
  const content: string;
  export default content;
}
