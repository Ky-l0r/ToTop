import type { SqlExecutor } from "../db/executor";
import type { SolutionStep } from "../solve/contracts";
import type { MaterialDraft } from "../solve/material-parse";
import { createId } from "../util/id";

/**
 * 资料库：只存"导入的标准答案"。
 *
 * 拍题解答不进这张表（L-1）。原因是两种东西混在一起之后，
 * 学生分不清哪份是老师给的、哪份是 AI 算的，而这两者的可信度完全不同。
 *
 * 一张卡 = 一份材料：题干、答案、解析步骤、易错点、来源。
 * 另有 `explanation` 存"让 AI 讲一遍"的结果——它是副本，不覆盖原文。
 */

export interface MaterialListItem {
  id: string;
  subject: string;
  title: string;
  /** 答案首段，卡片上显示。 */
  answerExcerpt: string;
  origin: string;
  createdAtUtc: number;
  hasExplanation: boolean;
  kpointName: string | null;
}

export interface MaterialDetail extends MaterialListItem {
  stem: string;
  answer: string;
  steps: SolutionStep[];
  pitfalls: string[];
  explanation: string;
  imagePath: string | null;
}

export interface MaterialFilter {
  subject?: string | null;
  kpointId?: string | null;
}

export async function createMaterial(
  db: SqlExecutor,
  draft: MaterialDraft,
  options: { nowUtc: number; origin?: string; imagePath?: string | null; random?: () => number } = {
    nowUtc: Date.now(),
  },
): Promise<string> {
  const id = createId("mt", options.nowUtc, options.random ?? Math.random);
  await db.run(
    `INSERT INTO material
       (id, subject, kpointId, title, stem, stepsJson, answer, source, origin, note,
        explanation, pitfallsJson, imagePath, createdAtUtc)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      id,
      draft.subject,
      null,
      draft.title,
      draft.stem,
      JSON.stringify(draft.steps),
      draft.answer,
      "standard_answer",
      options.origin ?? "导入",
      draft.warnings.join("；"),
      "",
      JSON.stringify(draft.pitfalls),
      options.imagePath ?? null,
      options.nowUtc,
    ],
  );
  return id;
}

export async function listMaterials(
  db: SqlExecutor,
  filter: MaterialFilter = {},
  limit = 100,
): Promise<MaterialListItem[]> {
  const params: (string | number)[] = [];
  let where = " WHERE 1 = 1";
  if (filter.subject) {
    where += " AND m.subject = ?";
    params.push(filter.subject);
  }
  if (filter.kpointId) {
    where += " AND m.kpointId = ?";
    params.push(filter.kpointId);
  }

  const rows = await db.all<{
    id: string;
    subject: string;
    title: string;
    answer: string;
    origin: string;
    createdAtUtc: number;
    explanation: string;
    kpointName: string | null;
  }>(
    `SELECT m.id AS id, m.subject AS subject, m.title AS title, m.answer AS answer,
            m.origin AS origin, m.createdAtUtc AS createdAtUtc, m.explanation AS explanation,
            k.name AS kpointName
       FROM material m
       LEFT JOIN knowledge_point k ON k.id = m.kpointId
      ${where}
      ORDER BY m.createdAtUtc DESC
      LIMIT ?`,
    [...params, limit],
  );

  return rows.map((row) => ({
    id: row.id,
    subject: row.subject,
    title: row.title || "未命名资料",
    answerExcerpt: excerpt(row.answer),
    origin: row.origin,
    createdAtUtc: Number(row.createdAtUtc ?? 0),
    hasExplanation: (row.explanation ?? "").trim() !== "",
    kpointName: row.kpointName,
  }));
}

export async function getMaterial(db: SqlExecutor, id: string): Promise<MaterialDetail | null> {
  const rows = await db.all<{
    id: string;
    subject: string;
    title: string;
    stem: string;
    answer: string;
    stepsJson: string;
    pitfallsJson: string;
    explanation: string;
    origin: string;
    createdAtUtc: number;
    imagePath: string | null;
    kpointName: string | null;
  }>(
    `SELECT m.id AS id, m.subject AS subject, m.title AS title,
            m.stem AS stem, m.answer AS answer, m.stepsJson AS stepsJson,
            m.pitfallsJson AS pitfallsJson, m.explanation AS explanation,
            m.origin AS origin, m.createdAtUtc AS createdAtUtc, m.imagePath AS imagePath,
            k.name AS kpointName
       FROM material m
       LEFT JOIN knowledge_point k ON k.id = m.kpointId
      WHERE m.id = ?`,
    [id],
  );
  const row = rows[0];
  if (!row) return null;

  return {
    id: row.id,
    subject: row.subject,
    title: row.title || "未命名资料",
    answerExcerpt: excerpt(row.answer),
    origin: row.origin,
    createdAtUtc: Number(row.createdAtUtc ?? 0),
    hasExplanation: (row.explanation ?? "").trim() !== "",
    kpointName: row.kpointName,
    // note 列存的是题干（导入时写在 note 里），保持与 0.8.0 的列语义一致
    stem: row.stem ?? "",
    answer: row.answer,
    steps: parseSteps(row.stepsJson),
    pitfalls: parseStringArray(row.pitfallsJson),
    explanation: row.explanation ?? "",
    imagePath: row.imagePath,
  };
}

/** 保存"让 AI 讲一遍"的结果。它是副本，不覆盖原文。 */
export async function saveExplanation(
  db: SqlExecutor,
  id: string,
  explanation: string,
): Promise<void> {
  await db.run("UPDATE material SET explanation = ? WHERE id = ?", [explanation, id]);
}

export async function deleteMaterial(db: SqlExecutor, id: string): Promise<void> {
  await db.run("DELETE FROM material WHERE id = ?", [id]);
}

export async function countMaterials(db: SqlExecutor): Promise<number> {
  const rows = await db.all<{ total: number }>("SELECT COUNT(*) AS total FROM material");
  return Number(rows[0]?.total ?? 0);
}

function excerpt(answer: string): string {
  const flat = answer.replace(/\s+/g, " ").trim();
  if (flat === "") return "（没有答案）";
  return flat.length <= 48 ? flat : `${flat.slice(0, 48)}…`;
}

function parseSteps(raw: string): SolutionStep[] {
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (item): item is SolutionStep =>
        typeof item === "object" && item !== null && typeof (item as SolutionStep).content === "string",
    );
  } catch {
    return [];
  }
}

function parseStringArray(raw: string): string[] {
  try {
    const parsed = JSON.parse(raw) as unknown;
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string") : [];
  } catch {
    return [];
  }
}
