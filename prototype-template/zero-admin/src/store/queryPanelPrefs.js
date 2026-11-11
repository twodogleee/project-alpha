import { defineStore } from "pinia";
import { ref } from "vue";
import { APP_STORAGE_KEYS } from "@/constants/storage.js";

const STORE_VERSION = 1;
const MAX_PAGE_PREFS = 80;
const MAX_PREF_AGE = 1000 * 60 * 60 * 24 * 180;

/** 规范化查询面板的页面键。 */
const normalizePageKey = (pageKey) => {
  return String(pageKey || "").split("?")[0] || "default";
};

/** 过滤不存在于当前表格列中的重复字段。 */
const uniqueExistingProps = (props = [], availableProps = []) => {
  const availableSet = new Set(availableProps);
  return [...new Set(props)].filter((prop) => availableSet.has(prop));
};

/** 计算当前页面最终的固定列配置。 */
const normalizePinnedProps = (props = [], defaultPinnedProps = [], availableProps = []) => {
  const pinnedProps = uniqueExistingProps(props, availableProps);
  if (pinnedProps.length) {
    return pinnedProps;
  }

  const defaultProps = uniqueExistingProps(defaultPinnedProps, availableProps);
  if (defaultProps.length) {
    return defaultProps;
  }

  return availableProps.length ? [availableProps[0]] : [];
};

/** 持久化各业务页面查询面板偏好的 Store。 */
export const useQueryPanelPrefsStore = defineStore("queryPanelPrefs", () => {
  const pagePrefs = ref({});

  /** 删除过期或超出数量上限的页面偏好。 */
  const prunePrefs = () => {
    const now = Date.now();
    const entries = Object.entries(pagePrefs.value)
      .filter(([, value]) => now - Number(value?.updatedAt || 0) <= MAX_PREF_AGE)
      .sort(([, a], [, b]) => Number(b?.updatedAt || 0) - Number(a?.updatedAt || 0))
      .slice(0, MAX_PAGE_PREFS);

    pagePrefs.value = Object.fromEntries(entries);
  };

  /** 读取指定页面的查询面板偏好。 */
  const getPrefs = (pageKey) => {
    return pagePrefs.value[normalizePageKey(pageKey)] || null;
  };

  /** 初始化并返回指定页面的查询面板偏好。 */
  const ensurePrefs = (pageKey, defaultPinnedProps = [], availableProps = []) => {
    const key = normalizePageKey(pageKey);
    const current = pagePrefs.value[key];
    const pinnedProps = normalizePinnedProps(
      current?.pinnedProps?.length ? current.pinnedProps : defaultPinnedProps,
      defaultPinnedProps,
      availableProps
    );

    pagePrefs.value[key] = {
      version: STORE_VERSION,
      expanded: current?.expanded === true,
      pinnedProps,
      updatedAt: current?.updatedAt || Date.now(),
    };

    prunePrefs();
    return pagePrefs.value[key];
  };

  /** 更新指定页面的查询面板偏好。 */
  const updatePrefs = (pageKey, patch, defaultPinnedProps = [], availableProps = []) => {
    const key = normalizePageKey(pageKey);
    const current = ensurePrefs(key, defaultPinnedProps, availableProps);
    const pinnedProps = normalizePinnedProps(
      patch.pinnedProps !== undefined ? patch.pinnedProps : current.pinnedProps,
      defaultPinnedProps,
      availableProps
    );

    pagePrefs.value[key] = {
      ...current,
      ...patch,
      pinnedProps,
      version: STORE_VERSION,
      updatedAt: Date.now(),
    };

    prunePrefs();
  };

  /** 重置指定页面的查询面板偏好。 */
  const resetPrefs = (pageKey, defaultPinnedProps = [], availableProps = []) => {
    const key = normalizePageKey(pageKey);
    pagePrefs.value[key] = {
      version: STORE_VERSION,
      expanded: false,
      pinnedProps: normalizePinnedProps(defaultPinnedProps, defaultPinnedProps, availableProps),
      updatedAt: Date.now(),
    };
    prunePrefs();
  };

  /** 删除指定页面的查询面板偏好。 */
  const removePrefs = (pageKey) => {
    delete pagePrefs.value[normalizePageKey(pageKey)];
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
    key: APP_STORAGE_KEYS.queryPanelPrefs,
    pick: ["pagePrefs"],
  },
});
