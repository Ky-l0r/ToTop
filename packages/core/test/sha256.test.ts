import { describe, expect, it } from "vitest";
import { base64ToBytes, sha256OfBase64, sha256OfText } from "../src/image/sha256";

/**
 * 用标准测试向量验：自己实现的哈希如果算错，缓存键会撞，
 * 表现是"这道题打开显示的是另一道题的解答"，而且极难复现。
 */
describe("SHA-256", () => {
  it("空串与标准向量对得上", () => {
    expect(sha256OfText("")).toBe(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    );
    expect(sha256OfText("abc")).toBe(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    );
    expect(sha256OfText("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")).toBe(
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
    );
  });

  it("跨分组边界的输入（55/56/64 字节）不出错", () => {
    // 55 字节：padding 刚好塞进同一个分组
    expect(sha256OfText("a".repeat(55))).toBe(
      "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318",
    );
    // 56 字节：必须多开一个分组
    expect(sha256OfText("a".repeat(56))).toBe(
      "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a",
    );
  });

  it("base64 输入与原文一致", () => {
    // "abc" 的 base64 是 YWJj
    expect(sha256OfBase64("YWJj")).toBe(sha256OfText("abc"));
    expect(sha256OfBase64("YWJj\n")).toBe(sha256OfText("abc"));
  });

  it("base64 解码认得 URL 安全变体", () => {
    const bytes = base64ToBytes("-_8");
    expect(Array.from(bytes)).toEqual([0xfb, 0xff]);
  });

  it("解码长度正确（含 1 个与 2 个填充）", () => {
    expect(base64ToBytes("YQ==").length).toBe(1);
    expect(base64ToBytes("YWI=").length).toBe(2);
    expect(base64ToBytes("YWJj").length).toBe(3);
  });
});
