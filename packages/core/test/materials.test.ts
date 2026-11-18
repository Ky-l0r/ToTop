import { describe, expect, it } from "vitest";
import { ensureSchema } from "../src/db/migrate";
import {
  countMaterials,
  createMaterial,
  deleteMaterial,
  getMaterial,
  listMaterials,
  saveExplanation,
} from "../src/repo/materials";
import { parseMaterialDraft } from "../src/solve/material-parse";
import { createHarness } from "./support/node-sqlite";

const NOW = 1_700_000_000_000;

const DRAFT = {
  subject: "math" as const,
  grade: "高二",
  title: "等差数列通项",
  stem: "已知 a_1=2，a_4=11，求通项。",
  answer: "a_n = 3n - 1",
  steps: [
    { idx: 1, content: "由 a_4 = a_1 + 3d 得 d = 3", why: "通项公式" },
    { idx: 2, content: "a_n = 3n - 1", why: "代入" },
  ],
  pitfalls: ["公差算成首项"],
  warnings: [],
};

async function seeded() {
  const { db, close } = createHarness();
  await ensureSchema(db);
  return { db, close };
}

describe("资料落库", () => {
  it("写入后能按卡片字段取回", async () => {
    const { db, close } = await seeded();
    try {
      const id = await createMaterial(db, DRAFT, { nowUtc: NOW, random: () => 0.5 });
      const list = await listMaterials(db);
      expect(list).toHaveLength(1);
      expect(list[0]?.title).toBe("等差数列通项");
      expect(list[0]?.answerExcerpt).toContain("3n - 1");
      expect(list[0]?.hasExplanation).toBe(false);
      expect(id.startsWith("mt_")).toBe(true);
    } finally {
      close();
    }
  });

  it("详情带题干、步骤与易错点", async () => {
    const { db, close } = await seeded();
    try {
      const id = await createMaterial(db, DRAFT, { nowUtc: NOW });
      const detail = await getMaterial(db, id);
      expect(detail?.stem).toContain("a_1=2");
      expect(detail?.steps).toHaveLength(2);
      expect(detail?.pitfalls).toEqual(["公差算成首项"]);
    } finally {
      close();
    }
  });

  it("AI 讲法单独存，不覆盖原文", async () => {
    const { db, close } = await seeded();
    try {
      const id = await createMaterial(db, DRAFT, { nowUtc: NOW });
      await saveExplanation(db, id, "先看公差是怎么来的……");
      const detail = await getMaterial(db, id);
      expect(detail?.explanation).toContain("公差");
      expect(detail?.answer).toBe("a_n = 3n - 1");
      expect((await listMaterials(db))[0]?.hasExplanation).toBe(true);
    } finally {
      close();
    }
  });

  it("按科目筛，删除后消失", async () => {
    const { db, close } = await seeded();
    try {
      const id = await createMaterial(db, DRAFT, { nowUtc: NOW });
      await createMaterial(
        db,
        { ...DRAFT, subject: "chinese", title: "文言实词" },
        { nowUtc: NOW + 1 },
      );
      expect(await listMaterials(db, { subject: "math" })).toHaveLength(1);
      expect(await countMaterials(db)).toBe(2);

      await deleteMaterial(db, id);
      expect(await countMaterials(db)).toBe(1);
      expect(await getMaterial(db, id)).toBeNull();
    } finally {
      close();
    }
  });

  it("没有答案的资料也能存，卡片上如实说明", async () => {
    const { db, close } = await seeded();
    try {
      await createMaterial(db, { ...DRAFT, answer: "" }, { nowUtc: NOW });
      expect((await listMaterials(db))[0]?.answerExcerpt).toBe("（没有答案）");
    } finally {
      close();
    }
  });
});

describe("导入契约解析", () => {
  it("正常输出逐字段读出", () => {
    const raw = JSON.stringify({
      subject: "math",
      grade: "高二",
      title: "等差数列通项",
      stem: "已知 a_1=2",
      answer: "a_n=3n-1",
      steps: [{ idx: 1, content: "求公差", why: "通项公式" }],
      pitfalls: ["公差算错"],
      warnings: [],
    });
    const parsed = parseMaterialDraft(raw);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.steps).toHaveLength(1);
    expect(parsed.value.pitfalls).toEqual(["公差算错"]);
  });

  it("缺答案时保留并记一笔，不判失败", () => {
    const raw = JSON.stringify({ subject: "math", stem: "已知 a_1=2", answer: "" });
    const parsed = parseMaterialDraft(raw);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.notes.join()).toContain("没有答案");
  });

  it("题干为空直接失败：这种资料存进去没用", () => {
    expect(parseMaterialDraft(JSON.stringify({ stem: "  ", answer: "x" })).ok).toBe(false);
  });

  it("学科乱填降级为 general", () => {
    const raw = JSON.stringify({ subject: "竞赛", stem: "题目" });
    const parsed = parseMaterialDraft(raw);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.subject).toBe("general");
  });
});
