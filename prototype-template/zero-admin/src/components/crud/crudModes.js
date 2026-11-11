/** 新增、编辑和详情三种公共工作模式。 */
export const CRUD_MODE = Object.freeze({
  CREATE: "create",
  EDIT: "edit",
  DETAIL: "detail",
});

/** 公共工作模式对应的默认标题。 */
export const CRUD_MODE_TITLE = Object.freeze({
  [CRUD_MODE.CREATE]: "新增",
  [CRUD_MODE.EDIT]: "编辑",
  [CRUD_MODE.DETAIL]: "详情",
});

/** 公共工作模式集合，用于组件运行时校验。 */
export const CRUD_MODE_VALUES = Object.freeze(Object.values(CRUD_MODE));

/** 新增模式可选择的标准容器。 */
export const CRUD_CREATE_CONTAINER = Object.freeze({
  DIALOG: "dialog",
  DRAWER: "drawer",
});

/** 新增模式标准容器集合，用于组件运行时校验。 */
export const CRUD_CREATE_CONTAINER_VALUES = Object.freeze(
  Object.values(CRUD_CREATE_CONTAINER)
);

/**
 * 判断给定值是否为脚手架支持的 CRUD 工作模式。
 *
 * @param {unknown} value 待校验的工作模式
 * @returns {boolean} true 表示可作为 CRUD 工作模式使用
 */
export const isCrudMode = (value) => {
  return CRUD_MODE_VALUES.includes(value);
};
