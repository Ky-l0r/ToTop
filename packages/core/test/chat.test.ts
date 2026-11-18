import { describe, expect, it } from "vitest";
import { buildChatMessages, isBlankQuestion, trimChat, type ChatTurn } from "../src/solve/chat";

function turns(count: number): ChatTurn[] {
  const out: ChatTurn[] = [];
  for (let i = 0; i < count; i += 1) {
    out.push({ role: "user", content: `问 ${i}` });
    out.push({ role: "assistant", content: `答 ${i}` });
  }
  return out;
}

describe("对话上下文", () => {
  it("没超窗口时原样返回", () => {
    const history = turns(2);
    expect(trimChat(history)).toEqual(history);
  });

  it("超出窗口只留最近几条", () => {
    const history = turns(10);
    const trimmed = trimChat(history, 8);
    expect(trimmed).toHaveLength(8);
    expect(trimmed[0]?.content).toBe("问 6");
    expect(trimmed.at(-1)?.content).toBe("答 9");
  });

  it("窗口起点落在助手上时往前挪一条，不出现没有提问的回答", () => {
    const history = turns(10);
    // 9 条：起点会落在 index 1（assistant），应前移到 index 0（user）
    const trimmed = trimChat(history, 7);
    expect(trimmed[0]?.role).toBe("user");
  });

  it("空历史不炸", () => {
    expect(trimChat([])).toEqual([]);
  });

  it("送进模型的是 系统 + 历史 + 这一问", () => {
    const messages = buildChatMessages("系统", turns(1), "再讲细一点");
    expect(messages[0]).toEqual({ role: "system", content: "系统" });
    expect(messages[1]?.content).toBe("问 0");
    expect(messages.at(-1)).toEqual({ role: "user", content: "再讲细一点" });
  });

  it("空白问题判为无效", () => {
    expect(isBlankQuestion("   ")).toBe(true);
    expect(isBlankQuestion("在吗")).toBe(false);
  });
});
