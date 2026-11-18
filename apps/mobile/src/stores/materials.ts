import { deleteMaterial, listMaterials, type MaterialListItem } from "@totop/core";
import { getDatabase } from "@/services/db";
import { importMaterialFromImages, ImportError } from "@/services/material-import";
import { defineStore } from "pinia";
import { ref } from "vue";

/** 资料库：只存导入的标准答案，卡片式展示。 */
export const useMaterialsStore = defineStore("materials", () => {
  const items = ref<MaterialListItem[]>([]);
  const subject = ref("");
  const loading = ref(true);
  const error = ref("");
  const importing = ref(false);
  const lastMessage = ref("");

  async function load(): Promise<void> {
    loading.value = true;
    error.value = "";
    try {
      const { db } = await getDatabase();
      items.value = await listMaterials(db, { subject: subject.value || null }, 200);
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : "读不到资料库";
    } finally {
      loading.value = false;
    }
  }

  function setSubject(next: string): void {
    subject.value = next;
    void load();
  }

  /**
   * 导入一页标准答案。
   *
   * 用户取消（没选图）时返回 false，不弹错——把"取消"当失败是最招人烦的一种提示。
   */
  async function importFrom(source: "camera" | "album"): Promise<boolean> {
    if (importing.value) return false;
    importing.value = true;
    error.value = "";
    lastMessage.value = "";
    try {
      const outcome = await importMaterialFromImages(source, "");
      if (!outcome) return false;
      lastMessage.value = `已导入：${outcome.draft.title}`;
      await load();
      return true;
    } catch (caught) {
      error.value = caught instanceof ImportError ? caught.message : "导入失败";
      return false;
    } finally {
      importing.value = false;
    }
  }

  async function remove(id: string): Promise<void> {
    try {
      const { db } = await getDatabase();
      await deleteMaterial(db, id);
      await load();
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : "删除失败";
    }
  }

  return { items, subject, loading, error, importing, lastMessage, load, setSubject, importFrom, remove };
});
