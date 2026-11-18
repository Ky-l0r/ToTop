import { describe, expect, it } from "vitest";
import { buildChatBody, toChunk } from "../src/llm/openai";
import type { LlmRequest } from "../src/llm/types";

describe("OpenAI 兼容协议接线", () => {
  it("请求体默认开流式并索要用量", () => {
    const body = buildChatBody({
      model: "qwen-vl",
      messages: [{ role: "system", content: "s" }, { role: "user", content: "u" }],
    });
    expect(body.stream).toBe(true);
    expect(body.stream_options).toEqual({ include_usage: true });
    expect(body.model).toBe("qwen-vl");
  });

  it("带图片的消息转成多模态内容块", () => {
    const body = buildChatBody({
      model: "m",
      messages: [{ role: "user", content: "看图", images: ["data:image/jpeg;base64,AAA"] }],
    });
    const messages = body.messages as Array<{ content: unknown }>;
    expect(messages[0]?.content).toEqual([
      { type: "text", text: "看图" },
      { type: "image_url", image_url: { url: "data:image/jpeg;base64,AAA" } },
    ]);
  });

  it("正文增量与思考增量分开，思考内容不许混进正文", () => {
    const reasoning = toChunk({
      event: "message",
      data: JSON.stringify({ choices: [{ delta: { reasoning_content: "想一想" } }] }),
    });
    expect(reasoning).toEqual({ kind: "reasoning", text: "想一想" });

    const content = toChunk({
      event: "message",
      data: JSON.stringify({ choices: [{ delta: { content: "答案是" } }] }),
    });
    expect(content).toEqual({ kind: "delta", text: "答案是" });
  });

  it("结束帧带用量与 finish_reason", () => {
    const chunk = toChunk({
      event: "message",
      data: JSON.stringify({
        choices: [{ delta: {}, finish_reason: "stop" }],
        usage: { prompt_tokens: 120, completion_tokens: 30 },
      }),
    });
    expect(chunk).toEqual({
      kind: "finished",
      usage: { inputTokens: 120, outputTokens: 30 },
      finishReason: "stop",
    });
  });

  it("[DONE] 与心跳不产生 chunk", () => {
    expect(toChunk({ event: "message", data: "[DONE]" })).toBeNull();
    expect(toChunk({ event: "message", data: "not-json" })).toBeNull();
  });

  it("流里的 error 帧转成失败", () => {
    const chunk = toChunk({
      event: "message",
      data: JSON.stringify({ error: { message: "quota exceeded" } }),
    });
    expect(chunk?.kind).toBe("failed");
  });

  it("temperature 与 max_tokens 只在给了的时候出现", () => {
    const minimal: LlmRequest = { model: "m", messages: [] };
    expect(buildChatBody(minimal)).not.toHaveProperty("temperature");
    const tuned: LlmRequest = { model: "m", messages: [], temperature: 0.2, maxTokens: 900 };
    expect(buildChatBody(tuned)).toMatchObject({ temperature: 0.2, max_tokens: 900 });
  });
});
