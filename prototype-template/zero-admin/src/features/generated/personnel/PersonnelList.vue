<script setup>
/** 人员样板列表：查询、分页、独立新增/编辑表单及共享模拟实体。@author Andersen */
import { computed, ref, reactive } from 'vue';
import { useRouter, onBeforeRouteLeave } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { ETable, CrudDialog, CrudDrawer, AsyncState } from '@/platform/components.js';
import { departments, personnelState, createPersonDraft, savePerson, departmentName } from '@/mock/generated/personnel-store.js';
import PersonFields from './PersonFields.vue';

/** 页面导航，只有用户点击详情时改变路由。 */
const router = useRouter();
/** 查询输入与已应用查询分离，输入不会立刻覆盖列表。 */
const queryForm = reactive({ name: '', departmentId: '' });
/** 已提交的查询条件。 */
const query = ref({ name: '', departmentId: '' });
/** 由页面拥有的分页状态，ETable 只通过请求上下文通知变化。 */
const pages = reactive({ pageIndex: 1, pageSize: 20 });
/** 演示情景不触发外部请求。 */
const scenario = ref('normal');
/** 当前编辑方式；空字符串表示未打开。 */
const editorMode = ref('');
/** 编辑中的独立值。 */
const draft = ref(createPersonDraft());
/** 打开表单时的值，用于关闭前检查。 */
const original = ref('');
/** 可见校验反馈，不会清空用户输入。 */
const formError = ref('');
/** 查询与分页共用的完整结果。 */
const filtered = computed(() => personnelState.people.filter((person) => person.name.includes(query.value.name.trim()) && (!query.value.departmentId || person.departmentId === query.value.departmentId)));
/** 当筛选结果减少时保证当前分页有效。 */
const pageIndex = computed(() => Math.min(pages.pageIndex, Math.max(1, Math.ceil(filtered.value.length / pages.pageSize))));
/** 新增弹窗受控状态。 */
const createVisible = computed({ get: () => editorMode.value === 'create', set: (visible) => { if (!visible) editorMode.value = ''; } });
/** 编辑抽屉受控状态。 */
const editVisible = computed({ get: () => editorMode.value === 'edit', set: (visible) => { if (!visible) editorMode.value = ''; } });
/** 表格列和查询字段是样板的业务配置。 */
const columns = [
  { prop: 'name', label: '人员姓名', minWidth: 140 },
  { prop: 'department', label: '所属部门', minWidth: 120 },
  { prop: 'email', label: '邮箱', minWidth: 200 },
  { prop: 'capacity', label: '资源容量', width: 100 },
  { prop: 'enabled', label: '状态', width: 100 },
];
/** 表格适配只传展示数据，不把实体可变引用交给公共组件。 */
const tableModule = computed(() => ({
  queryKey: 'personnel-sample', queryForm, columns,
  searchFields: [
    { prop: 'name', label: '人员姓名', type: 'input', attrs: { 'data-testid': 'query-name' }, placeholder: '按姓名搜索' },
    { prop: 'departmentId', label: '所属部门', type: 'select', options: departments, optionProps: { label: 'name', value: 'id' }, placeholder: '全部部门' },
  ],
  pages: { ...pages, pageIndex: pageIndex.value, total: filtered.value.length },
  dataList: filtered.value.slice((pageIndex.value - 1) * pages.pageSize, pageIndex.value * pages.pageSize).map((person) => ({ ...person, department: departmentName(person.departmentId) })),
  callback: handleQuery,
}));

/** 应用 ETable 请求上下文，重置时同时清空输入与已提交查询。 */
function handleQuery(context = {}) {
  if (context.resetQuery) Object.assign(queryForm, { name: '', departmentId: '' });
  query.value = { ...queryForm };
  pages.pageIndex = context.pageIndex || 1;
  pages.pageSize = context.pageSize || pages.pageSize;
}

/** 打开独立新增表单，不提前写入人员集合。 */
function openCreate() {
  draft.value = createPersonDraft(); original.value = JSON.stringify(draft.value); formError.value = ''; editorMode.value = 'create';
}

/** 编辑只复制当前实体；取消不会改变共享数据。 */
function openEdit(person) {
  const { department, ...fields } = person;
  draft.value = { ...fields }; original.value = JSON.stringify(draft.value); formError.value = ''; editorMode.value = 'edit';
}

/** 仅成功校验并写入后关闭，失败保留表单并显示原因。 */
async function handleSave() {
  try {
    savePerson(JSON.parse(JSON.stringify(draft.value)));
    original.value = JSON.stringify(draft.value); editorMode.value = ''; pages.pageIndex = 1; ElMessage.success('已保存到本次演示数据');
  } catch (error) { formError.value = error.message; return false; }
}

/** 关闭表单前保护未保存输入；取消确认时保持打开。 */
async function canClose() {
  if (JSON.stringify(draft.value) === original.value) return true;
  try {
    await ElMessageBox.confirm('未保存的修改将被放弃。', '放弃修改？', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning' });
    return true;
  } catch { return false; }
}

/** 离开列表页同样经过表单保护。 */
onBeforeRouteLeave(async () => !editorMode.value || await canClose());
</script>

<template>
  <section class="prototype-page" data-testid="personnel-page">
    <div class="prototype-page-title"><div><h1>人员管理</h1><p>维护人员基础资料与资源容量，查看所属部门及启用状态。</p></div><div class="prototype-page-tools"><span class="prototype-count">演示情景</span><el-select v-model="scenario" data-testid="scenario" style="width: 112px"><el-option label="正常" value="normal" /><el-option label="空数据" value="empty" /><el-option label="加载中" value="loading" /><el-option label="失败" value="error" /></el-select></div></div>
    <AsyncState v-if="scenario !== 'normal'" :loading="scenario === 'loading'" :empty="scenario === 'empty'" :error="scenario === 'error' ? '模拟读取失败，尚未发起真实请求' : null" @retry="scenario = 'normal'" />
    <ETable v-else data-testid="personnel-table" :table-module="tableModule" row-key="id" :column-settings="true" layout="total, prev, pager, next">
      <template #event><el-button data-testid="create-person" type="primary" :icon="Plus" @click="openCreate">新增人员</el-button><span class="prototype-count ml-3">共 {{ filtered.length }} 位人员</span></template>
      <template #cell-enabled="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" effect="light" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag></template>
      <template #tableColumn><el-table-column label="操作" width="130" fixed="right"><template #default="{ row }"><el-button :data-testid="`row-detail-${row.id}`" link type="primary" @click="router.push(`/personnel/${row.id}`)">详情</el-button><el-button :data-testid="`row-edit-${row.id}`" link type="primary" @click="openEdit(row)">编辑</el-button></template></el-table-column></template>
    </ETable>
    <CrudDialog v-model="createVisible" title="新增人员" :submit="handleSave" :before-close="canClose"><div v-if="formError" class="prototype-form-error" data-testid="form-error" role="alert">{{ formError }}</div><PersonFields v-model="draft" /></CrudDialog>
    <CrudDrawer v-model="editVisible" title="编辑人员" :submit="handleSave" :before-close="canClose"><div v-if="formError" class="prototype-form-error" data-testid="form-error" role="alert">{{ formError }}</div><PersonFields v-model="draft" /></CrudDrawer>
  </section>
</template>
