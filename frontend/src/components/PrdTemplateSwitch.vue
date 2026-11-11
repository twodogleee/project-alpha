<script setup>
/** 模板切换只提交版本引用；先呈现写作结构变化，不重建文档或调用模型。@author Andersen */
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { Check, LayoutTemplate, Loader2 } from '@lucide/vue';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { templateCatalog, prdTemplate, templateImpact, registerTemplateCatalog, isBuiltinTemplate, selectableTemplates } from '@/workspace/prd-template.js';
import { getPrdTemplateCatalog } from '@/api/prd-templates.js';
/** 当前草稿由父级管理，保存失败保留草稿并在面板内说明。 */
const props = defineProps({ open: Boolean, prd: { type: Object, required: true }, disabled: Boolean, error: String });
/** 应用由工作区统一持久化；选中卡片本身不会改变正文。 */
const emit = defineEmits(['update:open', 'apply']);
/** 默认选中当前绑定；升级只会由用户明确选择最新版本触发。 */
const selected = ref(`${prdTemplate(props.prd).id}@${prdTemplate(props.prd).revision}`);
/** 每次打开重新读取已发布版本，使他处新发布的模板立即可用。 */
const published = ref(null), loading = ref(false), catalogError = ref('');
/** 关闭后取消目录读取，迟到响应不覆盖下一次打开的选择。 */
let controller;
/** 每个模板显示最新发布版本，旧稿仍按自身引用读取且不自动勾选升级。 */
const choices = computed(() => selectableTemplates(published.value));
/** 当前文档和选中版本分别解析，禁止把最新版本当成历史文档版本。 */
const current = computed(() => prdTemplate(props.prd)), target = computed(() => {
  // 目录响应也是计算依赖，确保运行时注册的新增版本触发重新评估。
  const available = published.value?.templates || templateCatalog;
  return available.find(item => `${item.id}@${item.revision}` === selected.value) || current.value;
});
/** 同一个模板版本不重复写入历史。 */
const unchanged = computed(() => target.value?.id === current.value.id && target.value?.revision === current.value.revision);
/** 删除状态独立于版本定义，旧文档可继续编辑，但不能新选该模板。 */
const currentDeleted = computed(() => published.value?.deletedTemplateIds?.includes(current.value.id));
/** 即使选中值来自旧目录，也必须仍存在于最近的可选列表。 */
const targetSelectable = computed(() => choices.value.some(item => item.id === target.value?.id && item.revision === target.value?.revision));
/** 展示已有目录的标题与必填变化，完整列表可按需展开。 */
const impact = computed(() => target.value ? templateImpact(props.prd, target.value.id, target.value.revision) : null);
/** 刷新成功前禁止应用旧目录；失败保留当前文档和选择并提供原地重试。 */
async function refreshCatalog() {
  controller?.abort(); const request = new AbortController(); controller = request;
  loading.value = true; catalogError.value = '';
  try {
    const catalog = await getPrdTemplateCatalog(request.signal);
    if (request.signal.aborted) return;
    registerTemplateCatalog(catalog); published.value = catalog;
    if (!targetSelectable.value) selected.value = `${current.value.id}@${current.value.revision}`;
  } catch (failure) { if (!request.signal.aborted) catalogError.value = failure.message; }
  finally { if (!request.signal.aborted) loading.value = false; }
}
watch(() => props.open, open => {
  if (open) { selected.value = `${current.value.id}@${current.value.revision}`; refreshCatalog(); }
  else controller?.abort();
}, { immediate: true });
/** 保存拒绝可能来自另一窗口刚删除模板，重新取目录移除失效选择。 */
watch(() => props.error, error => { if (error && props.open) refreshCatalog(); });
onBeforeUnmount(() => controller?.abort());
</script>
<template>
  <Dialog :open="open" @update:open="emit('update:open', $event)">
    <DialogContent class="sm:max-w-2xl max-h-[90dvh] flex flex-col overflow-hidden">
      <DialogHeader><DialogTitle>切换 PRD 模板</DialogTitle><DialogDescription>当前：{{ current.name }} · v{{ current.revision }}。仅影响这份草稿，历史和已定稿版本保持原样。</DialogDescription></DialogHeader>
      <div class="template-body">
      <p v-if="loading" class="flex items-center gap-2 text-sm text-muted-foreground" role="status"><Loader2 class="size-4 animate-spin" />正在读取已发布模板…</p>
      <div v-if="catalogError" class="error-banner" role="alert">{{ catalogError }}<Button variant="outline" size="sm" class="ml-2" @click="refreshCatalog">重试</Button></div>
      <p v-if="currentDeleted && !loading && !catalogError" class="text-sm leading-6 text-muted-foreground" role="status">当前模板已删除。这份 PRD 仍按绑定版本正常编辑，也可以切换到其他模板。</p>
      <div v-if="!loading && !catalogError" class="template-choices" role="group" aria-label="选择 PRD 模板">
        <button v-for="item in choices" :key="`${item.id}@${item.revision}`" type="button" :aria-pressed="selected === `${item.id}@${item.revision}`" :disabled="disabled" @click="selected = `${item.id}@${item.revision}`">
          <span class="choice-title"><LayoutTemplate class="size-4" /><strong>{{ item.name }}</strong><Check v-if="selected === `${item.id}@${item.revision}`" class="size-4 choice-check" /></span>
          <span class="choice-description">{{ item.description }}</span><span class="choice-version">{{ isBuiltinTemplate(item.id) ? '内置模板' : '自定义模板' }} · v{{ item.revision }}{{ item.id === current.id && item.revision > current.revision ? ' · 有新版本' : '' }}</span>
        </button>
      </div>
      <div v-if="target" class="template-impact">
        <h3>{{ unchanged ? '当前模板' : '切换后的变化' }}</h3>
        <p>{{ target.guidance }}</p>
        <p>新增页面默认使用「{{ target.pageTypes.find(item => item.key === target.defaultPageType)?.title }}」。已有页面类型、正文和待确认关联全部保留。</p>
        <template v-if="!unchanged && impact">
          <details v-if="impact.renamed.length"><summary>{{ impact.renamed.length }} 处章节名称调整，正文保持原样</summary><ul><li v-for="item in impact.renamed" :key="item.key">{{ item.from }} → {{ item.to }}</li></ul></details>
          <details v-if="impact.added.length"><summary>增加 {{ impact.added.length }} 项定稿必填要求</summary><ul><li v-for="item in impact.added" :key="item">{{ item }}</li></ul></details>
          <details v-if="impact.removed.length"><summary>{{ impact.removed.length }} 项改为可选，已有内容继续保留</summary><ul><li v-for="item in impact.removed" :key="item">{{ item }}</li></ul></details>
          <p v-if="impact.reordered.length">章节顺序调整：{{ impact.reordered.join('、') }}。</p>
          <p v-if="!impact.added.length && !impact.removed.length">现有页面的定稿必填要求不变。</p>
        </template>
      </div>
      <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
      </div>
      <footer class="template-footer"><span>绑定具体版本，后续模板升级不会自动改动此文档。</span><Button variant="ghost" :disabled="disabled" @click="emit('update:open', false)">取消</Button><Button :disabled="disabled || loading || !!catalogError || unchanged || !targetSelectable" @click="emit('apply', target)">应用模板</Button></footer>
    </DialogContent>
  </Dialog>
</template>
<style scoped>
/* 多个自定义模板只滚动内容区，应用与取消始终在窗口内可见。 */
.template-body { min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; padding: 3px; }.template-footer { flex-shrink: 0; }
.template-choices { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-top: 8px; }.template-choices > button { text-align: left; border: 1px solid var(--border); border-radius: 8px; padding: 14px; display: flex; flex-direction: column; gap: 10px; }.template-choices > button[aria-pressed=true] { border-color: var(--primary); background: var(--accent); }.template-choices > button:focus-visible { outline: 2px solid var(--ring); outline-offset: 3px; }.choice-title { display: flex; align-items: center; gap: 6px; font-size: 13px; }.choice-title > svg { flex-shrink: 0; }.choice-check { margin-left: auto; }.choice-description { font-size: 12px; line-height: 1.8; color: var(--muted-foreground); flex: 1; }.choice-version { font-size: 11px; color: var(--muted-foreground); }
.template-impact { border-top: 1px solid var(--border); padding-top: 18px; font-size: 13px; line-height: 1.8; }.template-impact h3 { font-weight: 600; }.template-impact p { margin-top: 8px; color: var(--muted-foreground); }.template-impact details { margin-top: 10px; }.template-impact summary { cursor: pointer; }.template-impact ul { padding-left: 18px; list-style: disc; color: var(--muted-foreground); max-height: 160px; overflow: auto; }.template-impact li { margin-top: 4px; overflow-wrap: anywhere; }
.template-footer { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; border-top: 1px solid var(--border); padding-top: 16px; }.template-footer span { flex: 1; min-width: 180px; color: var(--muted-foreground); font-size: 12px; line-height: 1.7; }
/* 小屏逐行选择模板，防止名称和说明被挤成窄列。 */
@media (max-width: 600px) { .template-choices { grid-template-columns: 1fr; }.template-choices > button { padding: 12px; gap: 5px; } }
</style>
