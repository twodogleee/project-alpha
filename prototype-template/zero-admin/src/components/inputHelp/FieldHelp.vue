<template>
  <span ref="helpAnchor" hidden aria-hidden="true" />
  <span v-if="helpDisplay === 'inline'" class="field-help-inline"><slot>{{ text }}</slot></span>
  <el-tooltip
    v-if="isFloating"
    v-model:visible="visible"
    :trigger="showIcon ? ['hover', 'focus'] : []"
    :trigger-keys="[]"
    :virtual-triggering="!showIcon"
    :virtual-ref="effectiveTarget || undefined"
    placement="top" effect="light" :show-after="150" :hide-after="0"
    :enterable="false" popper-class="field-help-popper"
  >
    <template #content><slot>{{ text }}</slot></template>
    <button v-if="showIcon" type="button" class="field-help-button"
      :aria-label="`${label || '字段'}：填写说明`" :aria-describedby="descriptionId"
      @click.stop.prevent="visible = true" @keydown.esc.stop="visible = false">
      <el-icon aria-hidden="true"><QuestionFilled /></el-icon>
    </button>
  </el-tooltip>
  <Teleport v-if="helpDisplay !== 'none'" to="body"><span :id="descriptionId" class="field-help-sr-only">{{ text }}</span></Teleport>
</template>

<script setup>
/** 输入帮助只附加说明；动态控件使用容器事件委托，不接管输入和校验。@author Andersen */
import { computed, ref, shallowRef, useId, watch, onMounted, onActivated, onDeactivated, onBeforeUnmount } from 'vue';
import { QuestionFilled } from '@element-plus/icons-vue';
import { updateDescription } from './fieldHelp.js';
import './inputHelp.css';

/** 完整文本、自定义说明插槽和可选外部控件宿主。 */
const props = defineProps({
  /** 无障碍说明的纯文本，复杂可见内容通过默认插槽补充。 */
  text: { type: String, default: '' },
  /** 按钮的字段名称。 */
  label: { type: String, default: '' },
  /** 外部宿主；null 暂停自动帮助，undefined 自动关联表单内容容器。 */
  target: { type: Object, default: undefined },
  /** 展示与触发模式；直接使用时保留旧 tooltip 行为，可显式绑定项目策略。 */
  helpDisplay: { type: String, default: 'tooltip', validator: (value) => ['icon', 'control', 'tooltip', 'inline', 'none'].includes(value) },
  /** 无标签历史控件不插入按钮，通过原控件悬停和聚焦展示。 */
  showTrigger: { type: Boolean, default: true },
});
/** 隐藏定位点不占布局，control 模式无按钮时仍能找到表单宿主。 */
const helpAnchor = ref(null);
/** 浮层模式与固定说明、关闭模式互斥。 */
const isFloating = computed(() => ['icon', 'control', 'tooltip'].includes(props.helpDisplay));
/** 保留历史无标签场景的按钮关闭契约。 */
const showIcon = computed(() => props.showTrigger && ['icon', 'tooltip'].includes(props.helpDisplay));
/** 只有 control 和旧 tooltip 允许控件自动触发。 */
const controlTrigger = computed(() => ['control', 'tooltip'].includes(props.helpDisplay));
/** 自动发现的稳定表单内容容器。 */
const fieldTarget = shallowRef(null);
/** 显式宿主优先。 */
const effectiveTarget = computed(() => props.target === undefined ? fieldTarget.value : props.target);
/** 浮层可见状态。 */
const visible = ref(false);
/** KeepAlive 停用期间禁止旧宿主触发浮层。 */
let active = true;
/** 独立说明标识，保留业务已有描述。 */
const descriptionId = `field-help-${useId()}`;
/** 等待下拉展开状态更新的帧句柄。 */
let showFrame = 0;
/** 自动关联容器变化的局部观察器。 */
let fieldObserver;

/** 判断原控件的校验和选项浮层是否优先展示。 */
function shouldSuppressAutomaticHelp() {
  const target = effectiveTarget.value;
  const control = target?.closest('.el-select, .el-date-editor, .el-autocomplete') || target;
  return !active || !target?.isConnected || Boolean(target.closest('.is-error'))
    || control?.getAttribute('aria-expanded') === 'true'
    || Boolean(control?.querySelector('[aria-expanded="true"]'));
}

/** 原输入的悬停/聚焦只展示说明，不阻止其原事件。 */
function showFromControl(event) {
  // 字段容器里的展开、清空等操作按钮拥有自己的反馈，不自动叠加说明浮层。
  if (event?.type === 'focusin' && event.target.closest('button, [role="button"]')) {
    hideFromControl();
    return;
  }
  cancelAnimationFrame(showFrame);
  showFrame = requestAnimationFrame(() => {
    if (!shouldSuppressAutomaticHelp()) visible.value = true;
  });
}

/** 用户输入、离开或展开控件时关闭辅助浮层。 */
function hideFromControl() {
  cancelAnimationFrame(showFrame);
  visible.value = false;
}

/** 目标变化时清理旧监听和说明，内部输入替换则由局部观察器同步。 */
const stopTargetWatch = watch([effectiveTarget, () => props.helpDisplay], ([target], _previous, onCleanup) => {
  hideFromControl();
  if (!target || props.helpDisplay === 'none') return;
  const control = target.closest('.el-form-item')
    || target.closest('.el-select, .el-date-editor, .el-autocomplete') || target;
  let describedInputs = new Set();
  /** 更新说明关联，覆盖条件渲染、异步挂载和同一容器内的控件替换。 */
  function syncDescriptions() {
    const next = new Set(target.matches('input, textarea') ? [target] : target.querySelectorAll('input, textarea'));
    for (const input of describedInputs) if (!next.has(input)) updateDescription(input, descriptionId, false);
    for (const input of next) updateDescription(input, descriptionId, true);
    describedInputs = next;
  }
  const stateObserver = new MutationObserver(() => {
    syncDescriptions();
    if (controlTrigger.value && shouldSuppressAutomaticHelp()) hideFromControl();
  });
  stateObserver.observe(control, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'aria-expanded', 'aria-describedby'] });
  syncDescriptions();
  if (controlTrigger.value) {
    target.addEventListener('mouseenter', showFromControl);
    target.addEventListener('focusin', showFromControl);
    for (const event of ['mouseleave', 'focusout', 'input', 'keydown', 'pointerdown']) target.addEventListener(event, hideFromControl);
  }
  onCleanup(() => {
    stateObserver.disconnect();
    for (const input of describedInputs) updateDescription(input, descriptionId, false);
    target.removeEventListener('mouseenter', showFromControl);
    target.removeEventListener('focusin', showFromControl);
    for (const event of ['mouseleave', 'focusout', 'input', 'keydown', 'pointerdown']) target.removeEventListener(event, hideFromControl);
    hideFromControl();
  });
}, { immediate: true });

onMounted(() => {
  const field = helpAnchor.value?.closest('.el-form-item');
  if (!field) return;
  /** 始终维护自动宿主，以便显式目标或暂停状态切回 undefined 时恢复关联。 */
  const refreshTarget = () => { fieldTarget.value = field.querySelector('.el-form-item__content'); };
  fieldObserver = new MutationObserver(refreshTarget);
  fieldObserver.observe(field, { childList: true, subtree: true });
  refreshTarget();
});
onActivated(() => { active = true; });
onDeactivated(() => { active = false; hideFromControl(); });
onBeforeUnmount(() => { fieldObserver?.disconnect(); stopTargetWatch(); hideFromControl(); });
</script>
