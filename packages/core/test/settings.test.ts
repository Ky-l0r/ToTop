import { describe, expect, it } from "vitest";
import { ensureSchema } from "../src/db/migrate";
import { SETTINGS_KEYS, SettingsRepo } from "../src/repo/settings";
import { createHarness } from "./support/node-sqlite";

async function prepare(now: () => number = () => 1000) {
  const { db, close } = createHarness();
  await ensureSchema(db);
  return { repo: new SettingsRepo(db, now), close };
}

describe("设置读写", () => {
  it("写入后能读回，键名沿用旧版", async () => {
    const { repo, close } = await prepare();
    try {
      await repo.put(SETTINGS_KEYS.teachingMode, "DIRECT");
      expect(await repo.get(SETTINGS_KEYS.teachingMode)).toBe("DIRECT");
      // 键名必须与 0.8.0 一致，否则导入来的偏好不生效
      expect(SETTINGS_KEYS.teachingMode).toBe("mode");
    } finally {
      close();
    }
  });

  it("缺省时返回默认值，不返回 null 给调用方", async () => {
    const { repo, close } = await prepare();
    try {
      expect(await repo.getBoolean(SETTINGS_KEYS.ttsEnabled, true)).toBe(true);
      expect(await repo.getNumber(SETTINGS_KEYS.examClassSize, 40)).toBe(40);
      expect(await repo.get(SETTINGS_KEYS.subjects)).toBeNull();
    } finally {
      close();
    }
  });

  it("布尔与数字按文本存，读出来仍是原类型", async () => {
    const { repo, close } = await prepare();
    try {
      await repo.putBoolean(SETTINGS_KEYS.examGradeSize, false);
      await repo.putNumber(SETTINGS_KEYS.ttsSpeed, 1.25);
      expect(await repo.getBoolean(SETTINGS_KEYS.examGradeSize, true)).toBe(false);
      expect(await repo.getNumber(SETTINGS_KEYS.ttsSpeed, 1)).toBe(1.25);
    } finally {
      close();
    }
  });

  it("乱七八糟的数字文本回退到默认值，不让 NaN 流出去", async () => {
    const { repo, close } = await prepare();
    try {
      await repo.put(SETTINGS_KEYS.examTargetGlobal, "abc");
      expect(await repo.getNumber(SETTINGS_KEYS.examTargetGlobal, 0)).toBe(0);
    } finally {
      close();
    }
  });

  it("updatedAt 用注入的时间源，测试里可预期", async () => {
    let clock = 5000;
    const { repo, close } = await prepare(() => clock);
    try {
      await repo.put(SETTINGS_KEYS.firstLaunchDone, "true");
      const rows = await repo.getAll();
      expect(rows[SETTINGS_KEYS.firstLaunchDone]).toBe("true");
      clock = 6000;
      await repo.put(SETTINGS_KEYS.firstLaunchDone, "false");
      expect(await repo.get(SETTINGS_KEYS.firstLaunchDone)).toBe("false");
    } finally {
      close();
    }
  });

  it("删除后回到默认值", async () => {
    const { repo, close } = await prepare();
    try {
      await repo.put(SETTINGS_KEYS.inputMode, "voice");
      await repo.remove(SETTINGS_KEYS.inputMode);
      expect(await repo.get(SETTINGS_KEYS.inputMode)).toBeNull();
    } finally {
      close();
    }
  });
});
