/// <reference types="@dcloudio/types" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

declare const uni: any
declare const plus: any
declare const getCurrentPages: () => any[]
declare const wx: any
