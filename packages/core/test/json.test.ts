import { describe, expect, it } from "vitest";
import { extractJson, stripFence } from "../src/solve/json";

describe("从模型输出里捞 JSON", () => {
  it("去掉 ```json 围栏", () => {
    const raw = '```json\n{"a":1}\n```';
    expect(stripFence(raw)).toBe('{"a":1}\n');
    expect(extractJson(raw)).toEqual({ ok: true, value: { a: 1 } });
  });

  it("忽略 JSON 前后的客套话", () => {
    const raw = '好的，这是结果：\n{"stem":"x"}\n需要再补充吗？';
    expect(extractJson(raw)).toMatchObject({ ok: true, value: { stem: "x" } });
  });

  it("题干里的花括号与转义引号不会把配对算错", () => {
    const raw = '{"stem":"集合 {x|x>0} 与 \\"空集\\" 的关系","ask":"判断"}';
    const result = extractJson(raw);
    expect(result.ok).toBe(true);
    expect((result.value as { stem: string }).stem).toContain("{x|x>0}");
  });

  it("被截断时明确报截断，不补括号", () => {
    const result = extractJson('{"steps":[{"idx":1,"content":"配方"');
    expect(result.ok).toBe(false);
    expect(result.reason).toContain("截断");
  });

  it("空输出与非 JSON 各给一句话", () => {
    expect(extractJson("   ").reason).toContain("没有返回内容");
    expect(extractJson("我无法回答这个问题").reason).toContain("不是 JSON");
  });
});
