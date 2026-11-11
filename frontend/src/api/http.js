/** 同源 API 请求与安全错误契约；不记录正文或控制页面状态。@author Andersen */
import axios from 'axios';
import { projectProfile } from '@/project/profile.js';

/** 模型创建请求较慢，其他接口沿用同一取消与超时契约。 */
const client = axios.create({ baseURL: projectProfile.apiPrefix, timeout: 120000 });

/** 解包响应，同时保留业务代码，供页面区分版本冲突。 */
export async function request(config) {
  try {
    const response = await client.request(config);
    if (response.data.code !== 'SUCCESS') throw new Error(response.data.message || '请求失败');
    return response.data.data;
  } catch (error) {
    const message = error.response?.data?.message
      || (error.code === 'ECONNABORTED' ? '处理超时，请保留输入并稍后重试' : '请求失败，请检查后端服务是否启动');
    const failure = new Error(error.response ? message : (axios.isAxiosError(error) ? message : error.message));
    failure.code = error.response?.data?.code || error.code;
    throw failure;
  }
}
