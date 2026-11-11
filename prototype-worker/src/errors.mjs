/** 安全的 Worker 失败类型；错误信息不包含宿主环境或模型原始输出。@author Andersen */
export class PrototypeError extends Error {
  /** 构建可供 CLI 和后续后端适配器识别的错误码。 */
  constructor(code, message) {
    super(message);
    /** 稳定机器错误码。 */
    this.code = code;
    /** 错误类型名称。 */
    this.name = 'PrototypeError';
  }
}
