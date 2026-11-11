/** 人员样板共享模拟实体；只改变本次预览内存，不发送业务请求。@author Andersen */
import { reactive } from 'vue';
import seed from './personnel.json' with { type: 'json' };

/** 不变的部门字典，由列表、编辑与详情共用。 */
export const departments = Object.freeze(seed.departments.map((item) => Object.freeze({ ...item })));
/** 响应式人员列表；页面不得直接修改记录，统一使用保存方法。 */
export const personnelState = reactive({ people: structuredClone(seed.people) });

/** 根据稳定记录 ID 返回当前实体，未找到时由页面展示空态。 */
export function findPerson(id) {
  return personnelState.people.find((person) => person.id === id) || null;
}

/** 创建独立新增表单；默认值仅为样板规范，不代表未知 PRD 要求。 */
export function createPersonDraft() {
  return { id: '', name: '', departmentId: 'engineering', email: '', capacity: 1, enabled: true, notes: '' };
}

/** 在共享存储边界校验必填、关联、邮箱与容量，失败不写入。 */
export function validatePerson(person) {
  if (!String(person.name || '').trim()) return '请填写人员姓名';
  if (!departments.some((department) => department.id === person.departmentId)) return '请选择有效部门';
  if (!Number.isInteger(Number(person.capacity)) || Number(person.capacity) < 0 || Number(person.capacity) > 100) return '资源容量应为 0 到 100 的整数';
  if (person.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(person.email)) return '请填写有效邮箱';
  return '';
}

/** 保存完整独立表单，保留 0 和 false；保存后所有页面读取同一实体。 */
export function savePerson(draft) {
  const error = validatePerson(draft);
  if (error) throw new Error(error);
  const saved = { ...structuredClone(draft), id: draft.id || `person-${crypto.randomUUID()}`, name: draft.name.trim(), capacity: Number(draft.capacity), enabled: Boolean(draft.enabled) };
  const index = personnelState.people.findIndex((person) => person.id === saved.id);
  if (index < 0) personnelState.people.unshift(saved);
  else personnelState.people.splice(index, 1, saved);
  return saved;
}

/** 原地恢复种子记录，保持使用共享响应式对象的页面引用有效。 */
export function resetPersonnel() {
  personnelState.people.splice(0, personnelState.people.length, ...structuredClone(seed.people));
}

/** 将部门 ID 显示为字典名称；未知关联保持可见而非猜测。 */
export function departmentName(id) {
  return departments.find((department) => department.id === id)?.name || '未知部门';
}
