<script setup>
/** 模板草稿按层级局部编辑；只输出定义副本，保存和发布由系统配置页面负责。@author Andersen */
import { computed, ref, watch } from 'vue';
import { ArrowDown, ArrowUp, Pencil } from '@lucide/vue';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Checkbox } from '@/components/ui/checkbox';
import SelectField from '@/components/form/SelectField.vue';
import { templateEditorAreas, templateEditorSections, templateRequiredKeys, templateBaseRequiredKeys, updateTemplateMetadata, updateTemplateSection, moveTemplateSection, setTemplateRequired } from '@/workspace/prd-template-editor.js';
/** 受控草稿来自父页面，禁用期间不发送变更。 */
const props = defineProps({ modelValue: { type: Object, required: true }, disabled: Boolean });
/** 不修改已发布目录对象，所有编辑返回新的草稿定义。 */
const emit = defineEmits(['update:modelValue']);
/** 一次仅展示一个层级，并在该层级内打开单个章节编辑器。 */
const area = ref('basic'), editing = ref('');
/** 页面必填规则按实际类型独立维护，初始查看草稿的默认类型。 */
const pageType = ref(props.modelValue.defaultPageType);
/** 当前可排序的章节，整体与全局约定保持两个固定分组。 */
const sections = computed(() => templateEditorSections(props.modelValue, area.value));
/** 必填勾选绑定当前层级或页面类型，不使用跨类型公共状态。 */
const required = computed(() => templateRequiredKeys(props.modelValue, area.value, pageType.value));
/** 基础必填项不可取消，使用说明与普通可配置项区分。 */
const baseRequired = computed(() => templateBaseRequiredKeys(area.value));
/** 切换层级收起局部编辑，输入已经进入父级草稿无需另存。 */
watch(area, () => { editing.value = ''; });
/** 所有编辑都遵守统一禁用状态。 */
function change(definition) { if (!props.disabled) emit('update:modelValue', definition); }
</script>

<template>
  <div class="template-editor">
    <nav class="editor-tabs" aria-label="模板编辑层级"><Button v-for="item in templateEditorAreas" :key="item.key" type="button" size="sm" :variant="area === item.key ? 'secondary' : 'ghost'" :aria-pressed="area === item.key" @click="area = item.key">{{ item.title }}</Button></nav>
    <div v-if="area === 'basic'" class="editor-basic">
      <div class="field"><label for="template-draft-name">模板名称</label><Input id="template-draft-name" :model-value="modelValue.name" maxlength="120" :disabled="disabled" @update:model-value="change(updateTemplateMetadata(modelValue, 'name', $event))" /></div>
      <div class="field"><label for="template-draft-description">适用场景</label><Textarea id="template-draft-description" :model-value="modelValue.description" maxlength="2000" rows="4" :disabled="disabled" @update:model-value="change(updateTemplateMetadata(modelValue, 'description', $event))" /></div>
      <div class="field"><label for="template-draft-default">新页面默认类型</label><SelectField id="template-draft-default" :model-value="modelValue.defaultPageType" :options="modelValue.pageTypes.map(type => ({ value: type.key, label: type.title }))" :disabled="disabled" @update:model-value="change(updateTemplateMetadata(modelValue, 'defaultPageType', $event))" /><p class="field-hint">只影响以后新增的页面，已有 PRD 的页面类型保持原样。</p></div>
      <p class="editor-note">通过上方层级调整章节名称、写作提示、顺序及定稿必填要求。标准章节和页面类型保留。</p>
    </div>
    <div v-else>
      <div v-if="area === 'page'" class="editor-page-type"><label for="template-draft-page-type">查看必填规则</label><SelectField id="template-draft-page-type" v-model="pageType" :options="modelValue.pageTypes.map(type => ({ value: type.key, label: type.title }))" /><p>以下勾选只影响当前页面类型，章节名称和顺序适用于全部页面。</p></div>
      <p v-else class="editor-note">勾选的章节在 PRD 定稿时必须填写。上移、下移只调整当前分组中的顺序。</p>
      <ol class="editor-sections">
        <li v-for="(section, index) in sections" :key="section.key" :class="{ expanded: editing === section.key }">
          <div class="editor-section-row">
            <span class="editor-section-number">{{ index + 1 }}</span><strong>{{ section.title || '未填写名称' }}</strong>
            <label class="editor-required"><Checkbox :model-value="required.includes(section.key)" :disabled="disabled || baseRequired.includes(section.key)" :aria-label="`${section.title || '未命名章节'}${baseRequired.includes(section.key) ? '基础必填' : '定稿必填'}`" @update:model-value="change(setTemplateRequired(modelValue, area, section.key, $event, pageType))" />{{ baseRequired.includes(section.key) ? '基础必填' : '必填' }}</label>
            <Button type="button" variant="ghost" size="icon" class="size-7" :disabled="disabled || index === 0" :aria-label="`上移${section.title}`" @click="change(moveTemplateSection(modelValue, area, section.key, -1))"><ArrowUp class="size-3.5" /></Button>
            <Button type="button" variant="ghost" size="icon" class="size-7" :disabled="disabled || index === sections.length - 1" :aria-label="`下移${section.title}`" @click="change(moveTemplateSection(modelValue, area, section.key, 1))"><ArrowDown class="size-3.5" /></Button>
            <Button type="button" variant="ghost" size="sm" :aria-expanded="editing === section.key" @click="editing = editing === section.key ? '' : section.key"><Pencil v-if="editing !== section.key" class="size-3.5" />{{ editing === section.key ? '收起' : '编辑' }}</Button>
          </div>
          <div v-if="editing === section.key" class="editor-section-fields">
            <div class="field"><label :for="`template-section-title-${area}-${section.key}`">章节名称</label><Input :id="`template-section-title-${area}-${section.key}`" :model-value="section.title" maxlength="120" :disabled="disabled" @update:model-value="change(updateTemplateSection(modelValue, area, section.key, 'title', $event))" /></div>
            <div class="field"><label :for="`template-section-hint-${area}-${section.key}`">写作提示</label><Textarea :id="`template-section-hint-${area}-${section.key}`" :model-value="section.hint" maxlength="2000" rows="4" :disabled="disabled" @update:model-value="change(updateTemplateSection(modelValue, area, section.key, 'hint', $event))" /><p class="field-hint">提示帮助编写需求，不会自动成为 PRD 正文或业务规则。</p></div>
          </div>
          <p v-else class="editor-section-hint">{{ section.hint || '暂无写作提示' }}</p>
        </li>
      </ol>
    </div>
  </div>
</template>

<style scoped>
.editor-tabs { display: flex; gap: 3px; flex-wrap: wrap; border-bottom: 1px solid var(--border); padding-bottom: 14px; margin-bottom: 20px; }.editor-basic { display: grid; gap: 22px; }.editor-note { color: var(--muted-foreground); font-size: 12px; line-height: 1.8; margin-bottom: 16px; }
.editor-page-type { display: grid; grid-template-columns: 100px minmax(0, 1fr); align-items: center; gap: 10px; margin-bottom: 20px; }.editor-page-type label { font-size: 12px; color: var(--muted-foreground); }.editor-page-type p { grid-column: 1 / -1; color: var(--muted-foreground); font-size: 12px; line-height: 1.8; }
.editor-sections { display: grid; gap: 10px; }.editor-sections > li { border: 1px solid var(--border); border-radius: 8px; padding: 12px; }.editor-section-row { display: flex; align-items: center; gap: 5px; }.editor-section-number { color: var(--muted-foreground); width: 18px; flex-shrink: 0; font-size: 12px; }.editor-section-row strong { flex: 1; min-width: 0; font-size: 13px; font-weight: 600; overflow-wrap: anywhere; }.editor-required { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted-foreground); white-space: nowrap; margin: 0 5px; }.editor-section-hint { font-size: 12px; line-height: 1.8; color: var(--muted-foreground); margin: 8px 0 0 23px; overflow-wrap: anywhere; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }.editor-section-fields { display: grid; gap: 16px; padding: 16px 0 4px; margin-top: 8px; border-top: 1px solid var(--border); }
/* 窄抽屉将章节标题单独成行，保留完整操作触达区。 */
@media (max-width: 560px) { .editor-section-row { flex-wrap: wrap; }.editor-section-row strong { flex-basis: calc(100% - 28px); }.editor-required { margin-left: 23px; margin-right: auto; } }
</style>
