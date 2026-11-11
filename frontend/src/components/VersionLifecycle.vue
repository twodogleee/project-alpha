<script setup>
import SelectField from '@/components/form/SelectField.vue';
/** 正式评审与 PRD 产物编辑，人工基线和保存副本严格分开。@author Andersen */
import { computed, ref, watch, onBeforeUnmount } from "vue";
import { toast } from "vue-sonner";
import { X } from "@lucide/vue";
import { latestRunFailure, isFailedRun } from "@/workspace/state.js";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import * as api from "@/api/workspace.js";
import {
  useWorkspacePage,
  useWorkspaceLeave,
} from "@/composables/useWorkspacePage.js";
import { confirmAction } from "@/lib/feedback.js";
import {
  cloneArtifact,
  manualPrd,
  canApplyPrd,
  prdMarkdown,
  artifactSource,
} from "@/workspace/lifecycle.js";
import { globalSectionsFor } from "@/workspace/prd-template.js";
/** 版本真值来自父页面；范围未保存时不允许确认产物。 */
const props = defineProps({
  version: { type: Object, required: true },
  projectArchived: Boolean,
  scopeDirty: Boolean,
});
/** 基线变更后刷新版本阶段，局部脏状态阻止父页面覆盖。 */
const emit = defineEmits(["changed", "dirty-change", "busy-change"]);
/** 正式产物、副本和只读历史。 */
const detail = ref(null),
  editRevision = ref(null),
  review = ref(null),
  prd = ref(null),
  savedReview = ref(""),
  savedPrd = ref(""),
  sourceBaselineId = ref(null),
  sourceRunId = ref(null),
  selected = ref("REVIEW"),
  candidate = ref(null),
  conflict = ref(false),
  reopenReason = ref(""),
  historyOpen = ref(false),
  histories = ref([]),
  baselines = ref([]),
  historyPage = ref(1),
  baselinePage = ref(1),
  historyMore = ref(false),
  baselineMore = ref(false),
  historyKind = ref("REVIEW");
/** 统一网络错误，失败保留输入。 */
const { read, act, busy, loading, error, errorCode } = useWorkspacePage();
/** 内容脏状态来自副本序列化，不依据服务端刷新猜测。 */
const reviewDirty = computed(
  () => detail.value && JSON.stringify(review.value) !== savedReview.value,
);
const prdDirty = computed(
  () =>
    detail.value &&
    JSON.stringify({ prd: prd.value, source: sourceBaselineId.value }) !==
      savedPrd.value,
);
const dirty = computed(() =>
  Boolean(reviewDirty.value || prdDirty.value || reopenReason.value.trim()),
);
/** 取消、归档和过期窗口不能修改产物。 */
const readonly = computed(
  () =>
    props.projectArchived ||
    props.version.archivedAt ||
    props.version.lifecycle !== "ACTIVE" ||
    conflict.value,
);
const reviewReadonly = computed(
  () =>
    readonly.value ||
    busy.value ||
    loading.value ||
    props.scopeDirty ||
    props.version.stage !== "REVIEW",
);
const prdReadonly = computed(
  () =>
    readonly.value ||
    busy.value ||
    loading.value ||
    props.scopeDirty ||
    props.version.stage !== "PRD",
);
/** 运行任务由后端恢复，不以页面计时推断成功。 */
const running = computed(() =>
  detail.value?.runs.find((r) => ["QUEUED", "RUNNING"].includes(r.status)),
);
/** 关闭仅影响当前查看；后续任务恢复或成功后不再突出旧错误。 */
const dismissedFailureId = ref(null);
const currentFailure = computed(() => latestRunFailure(detail.value?.runs, dismissedFailureId.value));
/** 历史失败可按任务编号查阅，不占据当前生成结果区域。 */
const failedRuns = computed(() => (detail.value?.runs || []).filter(isFailedRun));
/** 后台轮询与页实例代数，跨版本不会回写。 */
let timer,
  pollController,
  disposed = false,
  epoch = 0,
  attemptedGeneration = null,
  confirmations = {};
useWorkspaceLeave(dirty, busy, () => {
  savedReview.value = JSON.stringify(review.value);
  savedPrd.value = JSON.stringify({
    prd: prd.value,
    source: sourceBaselineId.value,
  });
  reopenReason.value = "";
});
watch(dirty, (value) => emit("dirty-change", value));
watch(busy, (value) => emit("busy-change", value));
/** 只有明确重载或保存成功才替换编辑副本。 */
function adopt(value, reset = false) {
  if (!reset && value.revision < editRevision.value) {
    schedule();
    return;
  }
  if (!reset && value.revision !== editRevision.value) {
    conflict.value = true;
    error.value =
      "正式产物已在其他窗口改变，本地输入保留。请明确重新载入后合并。";
  }
  detail.value = value;
  if (reset) {
    editRevision.value = value.revision;
    review.value = cloneArtifact(value.review);
    prd.value = value.prd ? cloneArtifact(value.prd) : null;
    sourceBaselineId.value = value.prdSourceBaselineId;
    sourceRunId.value = null;
    savedReview.value = JSON.stringify(review.value);
    savedPrd.value = JSON.stringify({
      prd: prd.value,
      source: sourceBaselineId.value,
    });
    conflict.value = false;
  }
  candidate.value = value.runs.find((r) => r.status === "SUCCEEDED") || null;
  schedule();
}
/** 初次读取只恢复已保存内容，绝不从候选自动填入。 */
async function load() {
  await read(
    (signal) => api.loadLifecycle(props.version.id, signal),
    (value) => adopt(value, true),
  );
}
/** 重新载入需明确放弃所有本地产物副本。 */
async function reload() {
  if (
    dirty.value &&
    !(await confirmAction({
      title: "重新载入正式产物",
      description: "未保存的评审和 PRD 编辑将被放弃，已保存记录和基线保留。",
      action: "放弃并载入",
    }))
  )
    return;
  reopenReason.value = "";
  await load();
  emit("changed");
}
/** 提交失败时只有并发冲突锁定编辑，普通校验错误仍可修改。 */
async function command(work, refreshVersion = false) {
  const result = await act(work);
  if (result) {
    adopt(result, true);
    if (refreshVersion) emit("changed");
  } else if (errorCode.value === "WORKSPACE_REVISION_CONFLICT")
    conflict.value = true;
  return result;
}
/** 新条目首次保存由后端保持未确认。 */
function addRequirement() {
  review.value.requirements.push({
    id: crypto.randomUUID(),
    title: "新需求",
    description: "",
    acceptance: "",
    confirmed: false,
    sourceSuggestionId: null,
  });
}
/** 澄清默认阻塞，需要明确处理结论。 */
function addQuestion() {
  review.value.questions.push({
    id: crypto.randomUUID(),
    text: "",
    blocking: true,
    status: "OPEN",
    answer: "",
    reason: "",
  });
}
/** 建议候选不会自动成为需求。 */
function addSuggestion() {
  review.value.suggestions.push({
    id: crypto.randomUUID(),
    text: "",
    decision: "PENDING",
    reason: "",
  });
}
/** 未保存副本移除仅在明确保存后影响当前产物，历史仍保留。 */
function removeItem(kind, id) {
  review.value[kind] = review.value[kind].filter((item) => item.id !== id);
}
/** 需求正文改动立即显示待确认，服务器再次验证同样规则。 */
function unconfirm(item) {
  item.confirmed = false;
}
/** 已保存的接受决定才限制更改，本次表单选择仍可撤回。 */
function savedAccepted(id) {
  return detail.value.review.suggestions.some(
    (s) => s.id === id && s.decision === "ACCEPTED",
  );
}
/** PRD 待确认问题在人工解决后显式移除，不靠章节非空假定解决。 */
function setUnresolved(text) {
  prd.value.unresolvedIssues = text
    .split("\n")
    .map((s) => s.trim())
    .filter(Boolean);
}
/** 保存不推进阶段，后端返回真实确认和建议接受状态。 */
async function saveReview() {
  if (
    await command(() =>
      api.saveReview(props.version.id, {
        expectedRevision: editRevision.value,
        review: review.value,
      }),
    )
  )
    toast.success("评审已保存；新增和改动的需求仍需确认");
}
/** 幂等人工确认键固定到已保存产物，失败重试不产生重复基线。 */
async function confirm(kind) {
  const d = detail.value;
  if (
    !(await confirmAction({
      title: kind === "REVIEW" ? "确认评审基线并进入 PRD" : "确认 PRD 基线",
      description:
        "将冻结当前已保存内容和来源。后续修改需求须记录原因并重新评审。确认 PRD 后进入原型等待阶段，交付仍未完成。",
      action: "确认当前基线",
    }))
  )
    return;
  const sourceRevision = kind === "REVIEW" ? d.reviewRevision : d.prdRevision;
  const key = `${kind}:${sourceRevision}`;
  confirmations[key] ||= crypto.randomUUID();
  const method = kind === "REVIEW" ? api.confirmReview : api.confirmPrd;
  const result = await command(
    () =>
      method(props.version.id, {
        expectedRevision: d.revision,
        sourceRevision,
        requestId: confirmations[key],
      }),
    true,
  );
  if (result) {
    delete confirmations[key];
    selected.value = kind === "REVIEW" ? "PRD" : "PRD";
    toast.success(
      kind === "REVIEW"
        ? "评审基线已确认，进入 PRD"
        : "PRD 基线已确认；进入会话工作台可查看原型能力",
    );
  }
}
/** 上游变更明确回到评审，旧基线和旧 PRD 不被删除。 */
async function reopen() {
  if (
    !(await confirmAction({
      title: "重新评审需求变更",
      description:
        "回到需求评审，旧基线和 PRD 保留为历史，当前 PRD 需要同步后才能确认。",
      action: "回到需求评审",
    }))
  )
    return;
  const result = await command(
    () =>
      api.reopenReview(props.version.id, {
        expectedRevision: detail.value.revision,
        expectedVersionRevision: props.version.revision,
        reason: reopenReason.value,
      }),
    true,
  );
  if (result) {
    reopenReason.value = "";
    selected.value = "REVIEW";
    toast.success("已回到评审，旧产物保留");
  }
}
/** 明确采用新基线后保留旧文档供人工合并，自动更新需求覆盖集合。 */
async function useCurrentBaseline() {
  if (
    prd.value &&
    !(await confirmAction({
      title: "采用当前评审基线",
      description:
        "保留正文作为编辑副本，改为引用当前基线。请逐章核对范围、规则和验收后保存；不会自动确认。",
      action: "采用并人工合并",
    }))
  )
    return;
  const b = detail.value.reviewBaseline;
  if (!prd.value) prd.value = manualPrd(b);
  else prd.value.requirementIds = b.review.requirements.map((r) => r.id);
  sourceBaselineId.value = b.id;
  sourceRunId.value = null;
}
/** 候选生成命令遇到 HTTP 超时复用请求标识；服务终态才显示结果。 */
async function generate() {
  const d = detail.value;
  if (
    !attemptedGeneration ||
    attemptedGeneration.baselineId !== d.reviewBaseline.id ||
    attemptedGeneration.expectedRevision !== d.revision
  )
    attemptedGeneration = {
      expectedRevision: d.revision,
      baselineId: d.reviewBaseline.id,
      requestId: crypto.randomUUID(),
    };
  const result = await act(() =>
    api.generatePrd(props.version.id, attemptedGeneration),
  );
  if (result) {
    attemptedGeneration = null;
    detail.value.runs = [
      result,
      ...detail.value.runs.filter((r) => r.id !== result.id),
    ];
    schedule();
  }
}
/** 过期候选不覆盖新 PRD；已有编辑需明确放弃。 */
async function applyCandidate() {
  if (!canApplyPrd(candidate.value, detail.value)) return;
  if (
    prdDirty.value &&
    !(await confirmAction({
      title: "应用候选 PRD",
      description: "将替换当前未保存的 PRD 副本。已保存内容和基线不变。",
      action: "应用到编辑副本",
    }))
  )
    return;
  prd.value = cloneArtifact(candidate.value.result);
  sourceRunId.value = candidate.value.id;
  sourceBaselineId.value = candidate.value.baselineId;
}
/** 保存当前 PRD 并保留候选生成出处，用户改动仍需人工确认。 */
async function savePrd() {
  if (
    await command(() =>
      api.savePrd(props.version.id, {
        expectedRevision: editRevision.value,
        sourceBaselineId: sourceBaselineId.value,
        sourceRunId: sourceRunId.value,
        prd: prd.value,
      }),
    )
  )
    toast.success("PRD 草稿已保存，尚未确认");
}
/** 有真实任务时安排轮询，失败和中断停止。 */
function schedule() {
  clearTimeout(timer);
  if (running.value && !disposed) timer = setTimeout(poll, 1400);
}
/** 轮询只更新服务器状态，局部未保存评审和 PRD 保持原样。 */
async function poll() {
  const token = epoch,
    id = props.version.id;
  pollController = new AbortController();
  try {
    const value = await api.loadLifecycle(id, pollController.signal);
    if (disposed || token !== epoch) return;
    adopt(value);
  } catch (e) {
    if (!disposed && token === epoch && !pollController.signal.aborted)
      error.value = "任务状态读取失败，可重新载入。未保存输入仍保留。";
  }
}
/** 不可变历史分页，不采用历史内容替换副本。 */
async function showHistory(kind, more = false) {
  const result = await act(() =>
    api.lifecycleHistory(
      props.version.id,
      kind,
      more ? historyPage.value + 1 : 1,
    ),
  );
  if (result) {
    histories.value = more
      ? [...histories.value, ...result.items]
      : result.items;
    historyPage.value = result.page;
    historyMore.value = result.hasMore;
    historyKind.value = kind;
    historyOpen.value = true;
  }
}
/** 历史基线单独加载以核对范围和来源链。 */
async function showBaselines(more = false) {
  const result = await act(() =>
    api.lifecycleBaselines(props.version.id, more ? baselinePage.value + 1 : 1),
  );
  if (result) {
    baselines.value = more
      ? [...baselines.value, ...result.items]
      : result.items;
    baselinePage.value = result.page;
    baselineMore.value = result.hasMore;
    histories.value = [];
    historyKind.value = "BASELINE";
    historyOpen.value = true;
  }
}
/** 下载纯文本文件，名称清理不改变正文。 */
function downloadText(text, name) {
  const url = URL.createObjectURL(
    new Blob([text], { type: "text/markdown;charset=utf-8" }),
  );
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = name.replace(/[^\p{L}\p{N}._-]/gu, "_") + ".md";
  anchor.click();
  URL.revokeObjectURL(url);
}
/** 当前 PRD 下载始终为草稿，正式下载只使用不可变基线。 */
function downloadDraft() {
  downloadText(
    prdMarkdown(
      props.version.label,
      detail.value.prd,
      detail.value.prdSourceBaselineId,
      false,
      detail.value.prdMode,
      detail.value.prdModel,
    ),
    `${props.version.label}-prd-draft-r${detail.value.prdRevision}`,
  );
}
/** 已确认下载引用基线正文，绝不使用未保存输入。 */
function downloadBaseline(b) {
  const text =
    b.kind === "PRD"
      ? prdMarkdown(b.label, b.prd, b.sourceBaselineId, true, b.mode, b.model)
      : `# ${b.label} · 已确认需求评审基线\n\n> 基线 ${b.id}\n\n## 目标\n${b.goal}\n\n## 范围\n${b.scope}\n\n` +
        b.review.requirements
          .map(
            (r) =>
              `## ${r.title}\n${r.description}\n\n验收标准：${r.acceptance}\n`,
          )
          .join("\n");
  downloadText(text, `${b.label}-${b.kind}-baseline-${b.id}`);
}
/** 可读 JSON 历史是纯文本，不执行外部输出。 */
function pretty(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}
watch(
  () => props.version.id,
  () => {
    epoch++;
    clearTimeout(timer);
    pollController?.abort();
    detail.value = null;
    candidate.value = null;
    dismissedFailureId.value = null;
    attemptedGeneration = null;
    confirmations = {};
    selected.value = props.version.stage === "REVIEW" ? "REVIEW" : "PRD";
    load();
  },
  { immediate: true },
);
/** 同页范围或阶段保存后更新服务器门槛；本地副本存在时要求明确重载。 */
watch(
  () => props.version.revision,
  () => {
    if (!detail.value) return;
    if (dirty.value) {
      conflict.value = true;
      error.value = "版本已改变，本地输入保留，请明确重新载入后合并。";
    } else load();
  },
);
onBeforeUnmount(() => {
  disposed = true;
  epoch++;
  clearTimeout(timer);
  pollController?.abort();
  emit("dirty-change", false);
  emit("busy-change", false);
});
</script>
<template>
  <section class="mt-8 lifecycle-artifacts">
    <div class="section-heading mb-4">
      <div class="flex gap-2">
        <Button
          :variant="selected === 'REVIEW' ? 'default' : 'outline'"
          @click="selected = 'REVIEW'"
          >正式需求评审</Button
        ><Button
          :variant="selected === 'PRD' ? 'default' : 'outline'"
          @click="selected = 'PRD'"
          >PRD</Button
        >
      </div>
      <Button variant="outline" :disabled="busy" @click="showBaselines()"
        >确认基线历史</Button
      >
      <Button variant="outline" :disabled="busy" @click="showHistory('EVENT')"
        >需求变更记录</Button
      >
    </div>
    <p v-if="loading && !detail" class="muted">正在读取正式产物…</p>
    <div v-if="error" class="error-banner mb-4">
      {{ error
      }}<Button variant="outline" size="sm" :disabled="busy" @click="reload"
        >重新载入产物</Button
      >
    </div>
    <div v-if="scopeDirty" class="notice-banner mb-4">
      请先保存或放弃上方目标与范围编辑，再修改正式产物。
    </div>
    <template v-if="detail">
      <div class="flex flex-wrap gap-2 mb-4">
        <Badge v-if="detail.prdRevision" variant="outline"
          >PRD 保存 #{{ detail.prdRevision }}</Badge
        ><Badge variant="outline">评审保存 #{{ detail.reviewRevision }}</Badge
        ><Badge v-if="detail.reviewBaseline" variant="secondary"
          >需求基线已确认</Badge
        ><Badge v-if="detail.prdBaseline" variant="secondary"
          >PRD 基线已确认</Badge
        ><Badge v-if="dirty" variant="outline">有未保存产物</Badge>
      </div>
      <div v-if="selected === 'REVIEW'" class="workspace-panel space-y-6">
        <div class="section-heading">
          <div>
            <h2>正式需求评审</h2>
            <p class="muted text-sm mt-1">
              先保存新增或修改内容，再逐项确认。建议接受不自动确认需求。
            </p>
          </div>
          <Button
            variant="ghost"
            :disabled="busy"
            @click="showHistory('REVIEW')"
            >评审保存记录</Button
          >
        </div>
        <div v-if="version.stage === 'REVIEW'" class="notice-banner">
          <strong>确认门槛</strong>
          <ul class="list-disc pl-5 mt-2">
            <li v-for="item in detail.reviewBlockers" :key="item">
              {{ item }}
            </li>
            <li v-if="!detail.reviewBlockers.length">
              已保存评审满足门槛，可以人工确认基线。
            </li>
          </ul>
        </div>
        <p v-else class="notice-banner">
          当前评审基线已冻结。修改需求须先记录原因并重新评审。
        </p>
        <div class="section-heading">
          <h3>正式需求 · {{ review.requirements.length }}</h3>
          <Button
            variant="outline"
            size="sm"
            :disabled="reviewReadonly || review.requirements.length >= 100"
            @click="addRequirement"
            >新增需求</Button
          >
        </div>
        <article
          v-for="(item, index) in review.requirements"
          :key="item.id"
          class="artifact-item space-y-3"
        >
          <div class="flex justify-between items-center">
            <Badge variant="outline">{{
              item.confirmed ? "已确认" : "待确认"
            }}</Badge
            ><Button
              variant="ghost"
              size="sm"
              :disabled="reviewReadonly || !!item.sourceSuggestionId"
              @click="removeItem('requirements', item.id)"
              >移除需求</Button
            >
          </div>
          <Input
            v-model="item.title"
            :aria-label="`需求 ${index + 1} 名称`"
            maxlength="120"
            :disabled="reviewReadonly"
            @update:model-value="unconfirm(item)"
          />
          <Textarea
            v-model="item.description"
            :aria-label="`需求 ${index + 1} 正文`"
            maxlength="12000"
            :disabled="reviewReadonly"
            @update:model-value="unconfirm(item)"
          />
          <label class="block text-sm"
            >验收标准<Textarea
              v-model="item.acceptance"
              :aria-label="`需求 ${index + 1} 验收标准`"
              maxlength="6000"
              class="mt-2"
              placeholder="如何验证此需求已经满足？"
              :disabled="reviewReadonly"
              @update:model-value="unconfirm(item)"
          /></label>
          <label class="inline-flex gap-2 items-center text-sm"
            ><input
              v-model="item.confirmed"
              type="checkbox"
              :disabled="reviewReadonly || !item.acceptance.trim()"
              :aria-label="`确认需求 ${index + 1}`"
            />确认此需求及验收标准</label
          >
          <p v-if="item.sourceSuggestionId" class="muted text-xs">
            由明确接受的建议形成，仍需人工确认。
          </p>
        </article>
        <div class="section-heading">
          <h3>澄清问题</h3>
          <Button
            variant="outline"
            size="sm"
            :disabled="reviewReadonly || review.questions.length >= 100"
            @click="addQuestion"
            >新增问题</Button
          >
        </div>
        <article
          v-for="(item, index) in review.questions"
          :key="item.id"
          class="artifact-item space-y-3"
        >
          <Textarea
            v-model="item.text"
            :aria-label="`问题 ${index + 1}`"
            maxlength="4000"
            :disabled="reviewReadonly"
            placeholder="需要明确的问题"
          />
          <div class="flex gap-4 items-center">
            <label class="inline-flex gap-2 items-center text-sm"
              ><input
                v-model="item.blocking"
                type="checkbox"
                :disabled="reviewReadonly"
                :aria-label="`问题 ${index + 1} 为阻塞问题`"
              />阻塞问题</label
            ><SelectField v-model="item.status" :aria-label="`问题 ${index + 1} 状态`" :disabled="reviewReadonly" class="w-auto min-w-32"
              :options="[{ value: 'OPEN', label: '未解决' }, { value: 'RESOLVED', label: '已解决' }, { value: 'DEFERRED', label: '延期' }]" /><Button
              variant="ghost"
              size="sm"
              :disabled="reviewReadonly"
              @click="removeItem('questions', item.id)"
              >移除问题</Button
            >
          </div>
          <Textarea
            v-model="item.answer"
            :aria-label="`问题 ${index + 1} 结论`"
            :disabled="reviewReadonly"
            maxlength="6000"
            placeholder="问题结论（不自动成为需求）"
          /><Input
            v-if="item.status === 'DEFERRED'"
            v-model="item.reason"
            :aria-label="`问题 ${index + 1} 延期理由`"
            :disabled="reviewReadonly"
            maxlength="2000"
            placeholder="延期理由；阻塞问题延期仍不能确认基线"
          />
        </article>
        <div class="section-heading">
          <h3>设计建议</h3>
          <Button
            variant="outline"
            size="sm"
            :disabled="reviewReadonly || review.suggestions.length >= 100"
            @click="addSuggestion"
            >新增建议</Button
          >
        </div>
        <article
          v-for="(item, index) in review.suggestions"
          :key="item.id"
          class="artifact-item space-y-3"
        >
          <Textarea
            v-model="item.text"
            :aria-label="`建议 ${index + 1}`"
            :disabled="reviewReadonly || savedAccepted(item.id)"
            maxlength="4000"
            placeholder="设计建议，尚未成为正式需求"
          />
          <div class="flex gap-3">
            <SelectField v-model="item.decision" :aria-label="`建议 ${index + 1} 处理`" :disabled="reviewReadonly || savedAccepted(item.id)" class="w-auto min-w-40"
              :options="[{ value: 'PENDING', label: '待处理' }, { value: 'ACCEPTED', label: '接受并形成待确认需求' }, { value: 'REJECTED', label: '拒绝' }, { value: 'DEFERRED', label: '延期' }]" /><Button
              variant="ghost"
              size="sm"
              :disabled="reviewReadonly || savedAccepted(item.id)"
              @click="removeItem('suggestions', item.id)"
              >移除建议</Button
            >
          </div>
          <Input
            v-model="item.reason"
            :aria-label="`建议 ${index + 1} 理由`"
            :disabled="reviewReadonly"
            maxlength="2000"
            placeholder="拒绝或延期理由"
          />
        </article>
        <div class="flex gap-3 justify-end">
          <Button
            :disabled="
              reviewReadonly ||
              (!reviewDirty &&
                !detail.reviewBlockers.some((b) => b.includes('范围已变化')))
            "
            @click="saveReview"
            >保存评审</Button
          ><Button
            variant="outline"
            :disabled="
              reviewReadonly ||
              dirty ||
              detail.reviewBlockers.length > 0 ||
              !!running
            "
            @click="confirm('REVIEW')"
            >确认评审基线并进入 PRD</Button
          >
        </div>
      </div>
      <div v-else class="workspace-panel space-y-6">
        <div class="section-heading">
          <div>
            <h2>PRD</h2>
            <p class="muted text-sm mt-1">
              {{
                detail.prdBaseline
                  ? "已确认；正式下载使用不可变基线"
                  : "候选或编辑草稿，尚未定稿"
              }}
            </p>
          </div>
          <Button variant="ghost" :disabled="busy" @click="showHistory('PRD')"
            >PRD 保存记录</Button
          >
        </div>
        <p v-if="!detail.reviewCurrent" class="notice-banner">
          需要先确认有效需求评审基线。旧 PRD 保留，不能自动采用新的需求范围。
        </p>
        <div v-else class="notice-banner">
          当前评审基线：<span class="font-mono text-xs">{{
            detail.reviewBaseline.id
          }}</span
          ><br />已确认需求
          {{ detail.reviewBaseline.review.requirements.length }} 项 ·
          {{ detail.reviewBaseline.createdAt }}
        </div>
        <div v-if="prd && !detail.prdCurrent" class="notice-banner">
          当前编辑或保存的 PRD 引用
          {{ sourceBaselineId || "尚未指定" }}，需要核对上游并显式采用当前基线。
        </div>
        <div class="flex flex-wrap gap-3">
          <Button
            variant="outline"
            :disabled="
              prdReadonly || !detail.reviewCurrent || !!running || reviewDirty
            "
            @click="useCurrentBaseline"
            >{{ prd ? "采用当前评审基线并合并" : "开始手工编写 PRD" }}</Button
          ><Button
            :disabled="
              prdReadonly || !detail.reviewCurrent || !!running || dirty
            "
            @click="generate"
            >{{ running ? "正在生成候选…" : "生成 PRD 候选" }}</Button
          ><Button
            variant="outline"
            :disabled="busy || dirty || !detail.prd"
            @click="downloadDraft"
            >下载已保存草稿</Button
          ><Button
            v-if="detail.prdBaseline"
            variant="outline"
            :disabled="busy"
            @click="downloadBaseline(detail.prdBaseline)"
            >下载已确认 PRD</Button
          >
        </div>
        <p v-if="running" role="status" class="muted">
          后台任务
          {{
            running.status === "QUEUED" ? "排队中" : "执行中"
          }}，切换页面不取消任务。
        </p>
        <div v-if="currentFailure" role="status" class="notice-banner flex items-start justify-between gap-3">
          <p class="whitespace-pre-wrap break-words">{{ currentFailure.errorMessage }}</p>
          <Button type="button" variant="ghost" size="icon" class="size-7 shrink-0" aria-label="关闭 PRD 失败提示"
            @click="dismissedFailureId = currentFailure.id"><X class="size-4" /></Button>
        </div>
        <details v-if="failedRuns.length" class="text-sm text-muted-foreground">
          <summary class="cursor-pointer">生成失败记录（{{ failedRuns.length }}）</summary>
          <div v-for="run in failedRuns" :key="run.id" class="mt-3 space-y-1 rounded-md border p-3">
            <p>任务：{{ run.id }} · {{ run.status === 'INTERRUPTED' ? '已中断' : '失败' }}</p>
            <p class="whitespace-pre-wrap break-words">{{ run.errorMessage }}</p>
          </div>
        </details>
        <div v-if="candidate" class="artifact-candidate space-y-3">
          <div class="flex justify-between">
            <strong>生成候选 · 不自动覆盖文档</strong
            ><Badge variant="outline">{{
              artifactSource(candidate.mode, candidate.model)
            }}</Badge>
          </div>
          <details>
            <summary>查看候选正文</summary>
            <pre class="saved-scope-preview mt-3">{{
              prdMarkdown(
                version.label,
                candidate.result,
                candidate.baselineId,
                false,
                candidate.mode,
                candidate.model,
              )
            }}</pre>
          </details>
          <Button
            variant="outline"
            :disabled="prdReadonly || !canApplyPrd(candidate, detail)"
            @click="applyCandidate"
            >应用候选到编辑副本</Button
          >
          <p
            v-if="!canApplyPrd(candidate, detail)"
            class="text-sm text-amber-700"
          >
            候选对应的基线或保存记录已经变化，不能直接覆盖。
          </p>
        </div>
        <template v-if="prd"
          ><p class="muted text-xs break-all">
            编辑副本引用评审基线：{{ sourceBaselineId }} · 需求覆盖
            {{ prd.requirementIds.length }} 项
          </p>
          <label
            v-for="[field, title] in globalSectionsFor(prd)"
            :key="field"
            class="block"
            ><span class="font-medium">{{ title }}</span
            ><Textarea
              v-model="prd[field]"
              :aria-label="`PRD ${title}`"
              class="mt-2 min-h-28"
              :disabled="prdReadonly"
              maxlength="8000" /></label
        ></template>
        <label v-if="prd" class="block"
          ><span class="font-medium">PRD 待确认事项</span
          ><Textarea
            :model-value="prd.unresolvedIssues.join('\n')"
            @update:model-value="setUnresolved"
            aria-label="PRD 待确认事项"
            :disabled="prdReadonly"
            maxlength="12000"
            class="mt-2"
            placeholder="每行一项；解决后更新对应章节，再移除此项。全部解决后才能确认基线。"
        /></label>
        <div v-if="!detail.prdBaseline" class="notice-banner">
          <strong>PRD 确认门槛</strong>
          <ul class="list-disc pl-5 mt-2">
            <li v-for="item in detail.prdBlockers" :key="item">{{ item }}</li>
            <li v-if="!detail.prdBlockers.length">
              已保存 PRD 满足必要内容与来源门槛，等待人工确认。
            </li>
          </ul>
        </div>
        <div class="flex gap-3 justify-end">
          <Button
            :disabled="
              prdReadonly ||
              !prd ||
              !prdDirty ||
              sourceBaselineId !== detail.reviewBaseline?.id
            "
            @click="savePrd"
            >保存 PRD 草稿</Button
          ><Button
            variant="outline"
            :disabled="
              prdReadonly || dirty || detail.prdBlockers.length > 0 || !!running
            "
            @click="confirm('PRD')"
            >确认 PRD 基线</Button
          >
        </div>
      </div>
      <div
        v-if="version.stage !== 'REVIEW' && !readonly"
        class="workspace-panel mt-6 space-y-3"
      >
        <h3>需求变更</h3>
        <p class="muted text-sm">
          改动上游范围或正式需求前先重新评审，旧基线及 PRD 保留为历史。
        </p>
        <Textarea
          v-model="reopenReason"
          aria-label="重新评审原因"
          maxlength="2000"
          placeholder="说明这次需求变更及重新评审原因"
          :disabled="busy || scopeDirty || !!running"
        /><Button
          variant="outline"
          :disabled="
            busy ||
            scopeDirty ||
            reviewDirty ||
            prdDirty ||
            !reopenReason.trim() ||
            !!running
          "
          @click="reopen"
          >记录原因并重新评审</Button
        >
      </div>
    </template>
    <Dialog :open="historyOpen" @update:open="historyOpen = $event"
      ><DialogContent class="history-dialog"
        ><DialogHeader
          ><DialogTitle>{{
            historyKind === "BASELINE"
              ? "不可变确认基线"
              : historyKind === "EVENT"
                ? "需求变更记录"
                : `${historyKind === "REVIEW" ? "评审" : "PRD"}保存记录`
          }}</DialogTitle
          ><DialogDescription
            >历史只读，查看不会覆盖编辑副本或推进阶段。</DialogDescription
          ></DialogHeader
        >
        <div class="max-h-[65vh] overflow-auto space-y-6">
          <template v-if="historyKind === 'BASELINE'"
            ><article v-for="b in baselines" :key="b.id" class="artifact-item">
              <strong
                >{{ b.kind === "REVIEW" ? "需求评审" : "PRD" }} · 保存 #{{
                  b.sourceRevision
                }}</strong
              >
              <p class="muted text-xs break-all mt-2">
                {{ b.id }} · {{ b.createdAt }}
              </p>
              <p class="muted text-xs mt-2">
                {{ artifactSource(b.mode, b.model) }} · 上游
                {{ b.sourceBaselineId || "需求评审起点" }}
              </p>
              <pre class="saved-scope-preview mt-3">{{
                JSON.stringify(b, null, 2)
              }}</pre>
              <Button
                variant="outline"
                class="mt-3"
                @click="downloadBaseline(b)"
                >下载此确认基线</Button
              >
            </article>
            <Button
              v-if="baselineMore"
              variant="outline"
              :disabled="busy"
              @click="showBaselines(true)"
              >更多确认基线</Button
            >
            <p v-if="!baselines.length" class="muted">
              尚无人工确认基线
            </p></template
          ><template v-else
            ><article
              v-for="h in histories"
              :key="h.revision"
              class="artifact-item"
            >
              <strong>保存 #{{ h.revision }}</strong>
              <p class="muted text-xs mt-2">
                {{ h.createdAt }} · {{ artifactSource(h.mode, h.model) }}
              </p>
              <pre class="saved-scope-preview mt-3">{{
                pretty(h.contentJson)
              }}</pre>
            </article>
            <Button
              v-if="historyMore"
              variant="outline"
              :disabled="busy"
              @click="showHistory(historyKind, true)"
              >更多保存记录</Button
            >
            <p v-if="!histories.length" class="muted">尚无保存记录</p></template
          >
        </div></DialogContent
      ></Dialog
    >
  </section>
</template>
