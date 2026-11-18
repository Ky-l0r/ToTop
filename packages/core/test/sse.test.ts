import { describe, expect, it } from "vitest";
import { SseParser } from "../src/llm/sse";

describe("SSE 增量解析", () => {
  it("一个 chunk 切在两帧之间也能拼回两条事件", () => {
    const parser = new SseParser();
    expect(parser.push('data: {"a":1}')).toEqual([]);
    expect(parser.push("\n\ndata: {\"a\":2}\n\n")).toEqual([
      { event: "message", data: '{"a":1}' },
      { event: "message", data: '{"a":2}' },
    ]);
  });

  it("一个 chunk 里塞三条事件，一次全交出去", () => {
    const parser = new SseParser();
    const events = parser.push("data: a\n\ndata: b\n\ndata: c\n\n");
    expect(events.map((event) => event.data)).toEqual(["a", "b", "c"]);
  });

  it("多行 data 用换行拼接", () => {
    const parser = new SseParser();
    const events = parser.push("data: 第一行\ndata: 第二行\n\n");
    expect(events[0]?.data).toBe("第一行\n第二行");
  });

  it("注释行与心跳被忽略，不产生事件也不报错", () => {
    const parser = new SseParser();
    expect(parser.push(": keep-alive\n\n")).toEqual([]);
  });

  it("CRLF 换行同样能切帧", () => {
    const parser = new SseParser();
    const events = parser.push("data: x\r\n\r\n");
    expect(events[0]?.data).toBe("x");
  });

  it("event 字段被带出来", () => {
    const parser = new SseParser();
    const events = parser.push("event: delta\ndata: hi\n\n");
    expect(events[0]).toEqual({ event: "delta", data: "hi" });
  });

  it("流结尾没有空行时，flush 把残留交出来", () => {
    const parser = new SseParser();
    expect(parser.push("data: tail")).toEqual([]);
    expect(parser.flush()).toEqual([{ event: "message", data: "tail" }]);
  });

  it("data 后的内容保留前后空格，只去掉规范允许的一个空格", () => {
    const parser = new SseParser();
    const events = parser.push("data:  两个空格\n\n");
    expect(events[0]?.data).toBe(" 两个空格");
  });
});
