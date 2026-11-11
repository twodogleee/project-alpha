import { readonly, ref } from "vue";

/**
 * 将可复制的简单值转换为剪贴板文本。
 *
 * @param {unknown} value 待复制值
 * @returns {string|null} 可复制文本；空值返回 null
 */
export const normalizeClipboardText = (value) => {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  return String(value);
};

/**
 * 使用浏览器剪贴板能力写入文本，并兼容非安全上下文。
 *
 * @param {string} text 待写入文本
 * @param {object} [environment] 可测试的浏览器环境
 * @returns {Promise<void>} 写入完成
 */
export const writeClipboardText = async (text, environment = {}) => {
  const navigatorRef = environment.navigatorRef ?? globalThis.navigator;
  const documentRef = environment.documentRef ?? globalThis.document;
  const secureContext = environment.secureContext ?? globalThis.isSecureContext;

  if (navigatorRef?.clipboard?.writeText && secureContext !== false) {
    await navigatorRef.clipboard.writeText(text);
    return;
  }

  if (!documentRef?.body || typeof documentRef.execCommand !== "function") {
    throw new Error("当前环境不支持写入剪贴板");
  }

  const textArea = documentRef.createElement("textarea");
  textArea.value = text;
  textArea.setAttribute("readonly", "");
  textArea.style.position = "fixed";
  textArea.style.left = "-9999px";
  textArea.style.top = "-9999px";
  documentRef.body.appendChild(textArea);
  textArea.focus();
  textArea.select();
  try {
    if (documentRef.execCommand("copy") === false) {
      throw new Error("浏览器拒绝复制操作");
    }
  } finally {
    textArea.remove();
  }
};

/**
 * 管理可复用复制动作，不绑定按钮布局和消息组件。
 *
 * @param {object} [options] 复制配置
 * @param {Function} [options.writer] 实际写入函数
 * @param {Function} [options.onSuccess] 复制成功回调
 * @param {Function} [options.onError] 复制失败回调
 * @returns {object} 复制状态和动作
 */
export const useClipboardAction = ({
  writer = writeClipboardText,
  onSuccess,
  onError,
} = {}) => {
  if (typeof writer !== "function") {
    throw new TypeError("useClipboardAction.writer 必须是函数");
  }

  const copying = ref(false);
  const error = ref(null);
  const lastCopiedValue = ref(undefined);

  /** 执行一次复制；空值不触发写入。 */
  const copy = async (value) => {
    const text = normalizeClipboardText(value);
    if (text === null || copying.value) {
      return false;
    }

    copying.value = true;
    error.value = null;
    try {
      await writer(text, value);
      lastCopiedValue.value = value;
      await onSuccess?.(value, text);
      return true;
    } catch (copyError) {
      error.value = copyError;
      await onError?.(copyError, value);
      throw copyError;
    } finally {
      copying.value = false;
    }
  };

  /** 清空最近一次结果和错误。 */
  const reset = () => {
    error.value = null;
    lastCopiedValue.value = undefined;
  };

  return Object.freeze({
    copying: readonly(copying),
    error: readonly(error),
    lastCopiedValue: readonly(lastCopiedValue),
    copy,
    reset,
  });
};
