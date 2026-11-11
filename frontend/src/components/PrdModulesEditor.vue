<script setup>
import SelectField from '@/components/form/SelectField.vue';
/** 功能模块和页面的结构化编辑；输出独立副本，由会话统一自动保存。@author Andersen */
import { ref } from 'vue';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { moduleSections, pageSections, pageTypes, sectionHints, visiblePageSections, newModule, newPage, modulePath, pagePath } from '@/workspace/prd-template.js';
/** 模块草稿由父级拥有；禁用时不发出任何修改。 */
const props = defineProps({ modules: { type: Array, default: () => [] }, disabled: Boolean });
/** 内容变更与讨论焦点独立通知父级。 */
const emit = defineEmits(['update:modules', 'focus']);
/** 可选正文展开只属于本地界面状态，不写入 PRD。 */
const expanded = ref({});
/** 修改独立副本，防止子组件原地修改父级已保存内容。 */
function edit(work) {
  if (props.disabled) return;
  const modules = JSON.parse(JSON.stringify(props.modules)); work(modules); emit('update:modules', modules);
}
/** 添加空模块，不隐含新的业务规则。 */
function addModule() { edit(modules => modules.push(newModule())); }
/** 新页面追加在当前模块尾部。 */
function addPage(index) { edit(modules => modules[index].pages.push(newPage())); }
/** 结构删除可通过主页面撤销或历史核对，不增加重复确认。 */
function removeModule(index) { edit(modules => modules.splice(index, 1)); }
/** 删除页面不影响同模块其他页面。 */
function removePage(index, page) { edit(modules => modules[index].pages.splice(page, 1)); }
/** 模块名称和正文分别更新。 */
function setModule(index, field, value, body = false) { edit(modules => { if (body) modules[index].sections[field] = value; else modules[index][field] = value; }); }
/** 页面改类型仍保留已有正文，原填写章节继续显示。 */
function setPage(index, page, field, value, body = false) { edit(modules => { if (body) modules[index].pages[page].sections[field] = value; else modules[index].pages[page][field] = value; }); }
/** 可选章节可按需编辑，避免不同页面类型都显示整套列表模板。 */
function fieldsFor(module, page) { return expanded.value[pagePath(module, page)] ? pageSections : visiblePageSections(page); }
/** 页面目录定位时可以展开相应内容，避免滚动到隐藏章节。 */
function reveal(path) {
  const parts = path.split(':');
  if (parts[0] === 'page' && parts.length === 4) expanded.value[parts.slice(0, 3).join(':')] = true;
}
defineExpose({ reveal });
</script>

<template>
  <section class="prd-modules" data-section="modules">
    <div class="module-heading"><div><h3>功能模块与页面</h3><p class="muted text-xs">按模块组织，按页面描述。未知规则保留为待确认事项。</p></div><Button v-if="!disabled" size="sm" variant="outline" :disabled="modules.length >= 30" @click="addModule">添加模块</Button></div>
    <p v-if="!modules.length" class="module-empty">还没有功能模块。可以添加模块和页面，也可以直接在会话中描述产品功能，由 Alpha 逐步整理。</p>
    <article v-for="(module, index) in modules" :key="module.id" class="module-card" :data-section="modulePath(module)">
      <div class="module-heading">
        <Input :model-value="module.title" :disabled="disabled" maxlength="120" :aria-label="`模块 ${index + 1} 名称`" @update:model-value="setModule(index, 'title', $event)" @focus="emit('focus', modulePath(module))" />
        <Button size="sm" variant="ghost" @click="emit('focus', modulePath(module))">讨论模块</Button>
        <Button v-if="!disabled" size="sm" variant="ghost" @click="removeModule(index)">移除模块</Button>
      </div>
      <details class="module-overview" open><summary>模块范围、规则与验收</summary>
        <section v-for="[key, title] in moduleSections" :key="key" class="module-field" :data-section="`${modulePath(module)}:${key}`">
          <label :for="`${modulePath(module)}:${key}`">{{ title }}</label>
          <Textarea :id="`${modulePath(module)}:${key}`" :model-value="module.sections[key] || ''" :disabled="disabled" maxlength="10000" rows="3" :placeholder="sectionHints[key]" @update:model-value="setModule(index, key, $event, true)" @focus="emit('focus', `${modulePath(module)}:${key}`)" />
        </section>
      </details>
      <div class="module-heading"><h4>页面详情 · {{ module.pages.length }}</h4><Button v-if="!disabled" size="sm" variant="outline" :disabled="module.pages.length >= 30" @click="addPage(index)">添加页面</Button></div>
      <p v-if="!module.pages.length" class="muted text-sm">添加列表、表单、详情、日历或其他页面，分别完善其字段和操作。</p>
      <section v-for="(page, pageIndex) in module.pages" :key="page.id" class="page-card" :data-section="pagePath(module, page)">
        <div class="module-heading">
          <Input :model-value="page.title" :disabled="disabled" maxlength="120" :aria-label="`${module.title} 页面 ${pageIndex + 1} 名称`" @update:model-value="setPage(index, pageIndex, 'title', $event)" @focus="emit('focus', pagePath(module, page))" />
          <SelectField :model-value="page.type" :disabled="disabled" :aria-label="`${page.title} 页面类型`" class="w-auto min-w-32"
            :options="pageTypes.map(([value, label]) => ({ value, label }))" @update:model-value="setPage(index, pageIndex, 'type', $event)" />
          <Button size="sm" variant="ghost" @click="emit('focus', pagePath(module, page))">讨论页面</Button>
          <Button v-if="!disabled" size="sm" variant="ghost" @click="removePage(index, pageIndex)">移除页面</Button>
        </div>
        <section v-for="[key, title] in fieldsFor(module, page)" :key="key" class="module-field" :data-section="`${pagePath(module, page)}:${key}`">
          <label :for="`${pagePath(module, page)}:${key}`">{{ title }}</label>
          <Textarea :id="`${pagePath(module, page)}:${key}`" :model-value="page.sections[key] || ''" :disabled="disabled" maxlength="10000" rows="3" :placeholder="sectionHints[key]" @update:model-value="setPage(index, pageIndex, key, $event, true)" @focus="emit('focus', `${pagePath(module, page)}:${key}`)" />
        </section>
        <Button size="sm" variant="ghost" @click="expanded[pagePath(module, page)] = !expanded[pagePath(module, page)]">{{ expanded[pagePath(module, page)] ? '收起未填写的可选章节' : '展开可选章节' }}</Button>
      </section>
    </article>
  </section>
</template>

<style scoped>
.prd-modules { margin: 24px 0; }
.module-heading { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 14px; }
.module-heading > input { flex: 1; min-width: 140px; }
h3, h4 { font-weight: 600; }
h3 { font-size: 16px; }
.module-empty { padding: 20px; border: 1px dashed var(--border); border-radius: 8px; color: var(--muted-foreground); font-size: 13px; line-height: 1.8; }
.module-card { padding: 18px; border: 1px solid var(--border); border-radius: 10px; margin-top: 18px; }
.module-overview { margin-bottom: 22px; }
summary { cursor: pointer; font-size: 13px; }
.module-field { margin: 16px 0; }
.module-field label { display: block; font-size: 13px; font-weight: 500; margin-bottom: 8px; }
.page-card { padding-top: 18px; border-top: 1px solid var(--border); margin-top: 18px; }
textarea { line-height: 1.7; }
</style>
