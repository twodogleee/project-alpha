import { defineStore } from "pinia";
import { ref } from "vue";
import { APP_STORAGE_KEYS } from "@/constants/storage.js";
import {
  normalizeTableColumnPageKey,
  normalizeVisibleColumnKeys,
} from "@/utils/tableColumnPrefs.js";

const STORE_VERSION = 1;
const MAX_PAGE_PREFS = 80;
const MAX_PREF_AGE = 1000 * 60 * 60 * 24 * 180;

/** 持久化各页面表格字段显示偏好的 Store。 */
export const useTableColumnPrefsStore = defineStore("tableColumnPrefs", () => {
  const pagePrefs = ref({});

  /** 删除过期或超过数量上限的表格字段偏好。 */
  const prunePrefs = () => {
    const now = Date.now();
    const entries = Object.entries(pagePrefs.value)
      .filter(([, value]) => now - Number(value?.updatedAt || 0) <= MAX_PREF_AGE)
      .sort(([, left], [, right]) =>
        Number(right?.updatedAt || 0) - Number(left?.updatedAt || 0)
      )
      .slice(0, MAX_PAGE_PREFS);
    pagePrefs.value = Object.fromEntries(entries);
  };

  /** 读取指定页面已经保存的表格字段偏好。 */
  const getPrefs = (pageKey) => {
    return pagePrefs.value[normalizeTableColumnPageKey(pageKey)] || null;
  };

  /**
   * 初始化表格字段偏好，并让新增的默认字段在旧偏好中自动可见。
   *
   * @param {string} pageKey 页面或表格稳定键
   * @param {Array<string>} defaultVisibleKeys 默认可见字段
   * @param {Array<string>} availableKeys 当前实际字段
   * @param {Array<string>} requiredKeys 不允许隐藏的字段
   * @returns {Object} 当前页面的表格字段偏好
   */
  const ensurePrefs = (
    pageKey,
    defaultVisibleKeys = [],
    availableKeys = [],
    requiredKeys = []
  ) => {
    const key = normalizeTableColumnPageKey(pageKey);
    const current = pagePrefs.value[key];
    const previousAvailableSet = new Set(current?.availableKeys || []);
    const newDefaultKeys = current
      ? defaultVisibleKeys.filter((columnKey) => !previousAvailableSet.has(columnKey))
      : [];
    const visibleSeed = current?.visibleKeys?.length
      ? [...current.visibleKeys, ...newDefaultKeys]
      : defaultVisibleKeys;
    const visibleKeys = normalizeVisibleColumnKeys(
      visibleSeed,
      defaultVisibleKeys,
      availableKeys,
      requiredKeys
    );

    pagePrefs.value[key] = {
      version: STORE_VERSION,
      visibleKeys,
      availableKeys: [...availableKeys],
      updatedAt: current?.updatedAt || Date.now(),
    };
    prunePrefs();
    return pagePrefs.value[key];
  };

  /** 保存指定页面正式采用的可见字段。 */
  const updatePrefs = (
    pageKey,
    visibleKeys = [],
    defaultVisibleKeys = [],
    availableKeys = [],
    requiredKeys = []
  ) => {
    const key = normalizeTableColumnPageKey(pageKey);
    pagePrefs.value[key] = {
      version: STORE_VERSION,
      visibleKeys: normalizeVisibleColumnKeys(
        visibleKeys,
        defaultVisibleKeys,
        availableKeys,
        requiredKeys
      ),
      availableKeys: [...availableKeys],
      updatedAt: Date.now(),
    };
    prunePrefs();
    return pagePrefs.value[key];
  };

  /** 将指定页面恢复为当前代码声明的默认可见字段。 */
  const resetPrefs = (
    pageKey,
    defaultVisibleKeys = [],
    availableKeys = [],
    requiredKeys = []
  ) => {
    return updatePrefs(
      pageKey,
      defaultVisibleKeys,
      defaultVisibleKeys,
      availableKeys,
      requiredKeys
    );
  };

  /** 删除指定页面的表格字段偏好。 */
  const removePrefs = (pageKey) => {
    delete pagePrefs.value[normalizeTableColumnPageKey(pageKey)];
  };

  return {
    pagePrefs,
    getPrefs,
    ensurePrefs,
    updatePrefs,
    resetPrefs,
    removePrefs,
    prunePrefs,
  };
}, {
  persist: {
    key: APP_STORAGE_KEYS.tableColumnPrefs,
    pick: ["pagePrefs"],
  },
});
