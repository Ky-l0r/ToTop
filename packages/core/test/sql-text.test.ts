import { describe, expect, it } from "vitest";
import { bindParams, quoteSqlValue } from "../src/db/sql-text";

describe("SQL 文本拼装", () => {
  it("字符串里的单引号翻倍，题干带引号也不会把语句冲断", () => {
    expect(quoteSqlValue("it's")).toBe("'it''s'");
    expect(quoteSqlValue("老师说：'先配方'")).toBe("'老师说：''先配方'''");
  });

  it("NULL 与数字原样，非有限数按 NULL", () => {
    expect(quoteSqlValue(null)).toBe("NULL");
    expect(quoteSqlValue(42)).toBe("42");
    expect(quoteSqlValue(Number.NaN)).toBe("NULL");
    expect(quoteSqlValue(Number.POSITIVE_INFINITY)).toBe("NULL");
  });

  it("按顺序替换占位符", () => {
    expect(bindParams("SELECT * FROM t WHERE a = ? AND b = ?", ["x", 2])).toBe(
      "SELECT * FROM t WHERE a = 'x' AND b = 2",
    );
  });

  it("占位符多于参数时直接报错，不静默补 null", () => {
    expect(() => bindParams("SELECT ? , ?", ["only"])).toThrowError(/占位符比参数多/);
  });

  it("反斜杠不转义：SQLite 里它不是转义符，多转一层反而写坏数据", () => {
    expect(quoteSqlValue("C:\\path")).toBe("'C:\\path'");
  });
});
