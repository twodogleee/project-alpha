/**
 * Alpha 的品牌与接口约定，业务页面通过此配置读取项目身份。
 * @author Andersen
 */
export const projectProfile = Object.freeze({
  /** 工作台品牌名称。 */
  name: 'project-alpha',
  /** 项目接口前缀，同时适用于开发代理与单 JAR 部署。 */
  apiPrefix: '/api/alpha',
  /** 页面输入上限，与后端校验契约保持一致。 */
  maxRequirementLength: 12000,
});
