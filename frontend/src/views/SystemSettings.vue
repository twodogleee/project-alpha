<script setup>
import SelectField from '@/components/form/SelectField.vue';
/** 平台/模型独立设置页面；目录、编辑副本和运行选择各自维护。@author Andersen */
import { computed, onBeforeUnmount, onMounted, reactive, watch } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from "vue-router";
import {
  Plus,
  Search,
  RefreshCw,
  Network,
  Bot,
  Pencil,
  Trash2,
  KeyRound,
  Check,
  AlertCircle,
  Loader2,
  FlaskConical,
  ShieldCheck,
} from "@lucide/vue";
import { toast } from "vue-sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Sheet,
  SheetContent,
  SheetTitle,
  SheetDescription,
} from "@/components/ui/sheet";
import {
  Table,
  TableHeader,
  TableHead,
  TableBody,
  TableRow,
  TableCell,
} from "@/components/ui/table";
import { confirmAction } from "@/lib/feedback.js";
import { useWorkspace } from "@/lib/workspace.js";
import {
  getModelConfiguration,
  saveModelPlatform,
  deleteModelPlatform,
  saveModelProfile,
  deleteModelProfile,
  selectModel,
  testModelConfiguration,
  fetchPlatformModels,
} from "@/api/model-config.js";
import {
  changeModelPlatform,
  catalogFetchPayload,
  createModelEditor,
  createPlatformEditor,
  modelConfigurationPayload,
  modelProtocols,
  platformPayload,
  validModelEditor,
  validPlatformEditor,
} from "@/model/configuration.js";
/** 页面类型由路由决定，不维护与导航竞争的页签状态。 */
const route = useRoute();
/** 保存设置后刷新共享运行信息。 */
const workspace = useWorkspace();
/** 当前目录和两种事务副本，临时密钥仅存在 editor 内存。 */
const state = reactive({
  catalog: null,
  selection: null,
  selectionBaseline: "",
  editor: null,
  baseline: "",
  kind: "",
  id: null,
  busy: false,
  operation: "",
  closing: false,
  conflict: false,
  error: "",
  testResult: null,
  remoteModels: null,
  search: "",
});
/** 当前请求句柄，串行操作避免提交互相覆盖。 */
let controller;
/** 卸载后不再回写状态。 */
let disposed = false;
/** 独立页面标题和目录种类。 */
const isPlatformPage = computed(() => route.path === "/settings/platforms");
/** 已保存模型只提供密钥是否配置，不提供原密钥。 */
const previousModel = computed(() =>
  state.catalog?.models.find((m) => m.id === state.id),
);
/** 模型获取只使用当前所选平台的已保存地址，与未保存的平台编辑互不混用。 */
const selectedPlatform = computed(() => state.kind === "model"
  ? state.catalog?.platforms.find((p) => p.id === state.editor?.platformId) : null);
/** 获取目录允许尚未填写模型名称/标识，但凭证清除与输入不能冲突。 */
const canFetchModels = computed(() => Boolean(selectedPlatform.value?.modelCatalogUrl
  && state.editor.apiKey.length <= 4096 && !(state.editor.clearApiKey && state.editor.apiKey.trim())));
/** 模型目录按公开平台名称展示和检索。 */
const platformNames = computed(() =>
  Object.fromEntries(
    (state.catalog?.platforms || []).map((p) => [p.id, p.name]),
  ),
);
/** 当前生效的公开模型。 */
const activeModel = computed(() =>
  state.catalog?.models.find(
    (m) => m.id === state.catalog.runtime.activeModelId,
  ),
);
/** 运行选择提交与模型表单保存分别维护。 */
const selectionDirty = computed(() =>
  Boolean(
    state.selection &&
      JSON.stringify(state.selection) !== state.selectionBaseline,
  ),
);
/** 编辑和未应用运行选择都需要离开保护。 */
const dirty = computed(() =>
  Boolean(
    (state.editor && JSON.stringify(state.editor) !== state.baseline) ||
      selectionDirty.value,
  ),
);
/** 保存基础校验与原密钥保留策略。 */
const valid = computed(() =>
  state.kind === "platform"
    ? validPlatformEditor(state.editor)
    : validModelEditor(
        state.editor,
        state.catalog?.platforms || [],
        previousModel.value,
      ),
);
/** 测试需新密钥或当前模型同平台的已有密钥。 */
const canTest = computed(
  () =>
    state.kind === "model" &&
    valid.value &&
    (state.editor.apiKey.trim() ||
      (!state.editor.clearApiKey &&
        previousModel.value?.apiKeyConfigured &&
        previousModel.value.platformId === state.editor.platformId)),
);
/** 真实模式只能使用已有密钥的模型。 */
const canSelect = computed(
  () =>
    state.selection?.mode === "demo" ||
    state.catalog?.models.some(
      (m) => m.id === state.selection?.activeModelId && m.apiKeyConfigured,
    ),
);
/** 平台保存过密钥时限制更换接入目标，避免凭证转发到其他地址。 */
const platformHasKeys = computed(
  () =>
    state.kind === "platform" &&
    state.catalog?.models.some(
      (m) => m.platformId === state.id && m.apiKeyConfigured,
    ),
);
/** 启用中的模型不能清除密钥。 */
const editingActiveModel = computed(
  () => state.kind === "model" && isActive(state.id),
);
/** 搜索只访问数据库公开元数据。 */
const filteredItems = computed(() => {
  const items = isPlatformPage.value
    ? state.catalog?.platforms
    : state.catalog?.models;
  const keyword = state.search.trim().toLocaleLowerCase();
  return (items || []).filter((item) =>
    [item.name, item.baseUrl, item.modelCatalogUrl, item.model, platformNames.value[item.platformId]]
      .filter(Boolean)
      .join(" ")
      .toLocaleLowerCase()
      .includes(keyword),
  );
});
/** 清除当前编辑和连接测试，不隐式消除目录并发冲突。 */
function clearEditor() {
  state.editor = null;
  state.kind = "";
  state.id = null;
  state.baseline = "";
  state.testResult = null;
  state.remoteModels = null;
}
/** 成功读取或保存后使用新的目录版本建立基准。 */
function adopt(catalog) {
  state.catalog = catalog;
  state.selection = { ...catalog.runtime };
  state.selectionBaseline = JSON.stringify(state.selection);
  clearEditor();
  state.conflict = false;
}
/** 执行串行请求；失败保留输入，冲突必须重载后才能继续写入。 */
async function run(action, operation = "") {
  if (state.busy) return;
  controller?.abort();
  const current = new AbortController();
  controller = current;
  state.busy = true;
  state.operation = operation;
  state.error = "";
  state.testResult = null;
  try {
    await action(current.signal);
  } catch (error) {
    if (!disposed && !current.signal.aborted) {
      state.error = error.message;
      state.conflict = state.conflict || error.code === "MODEL_CONFIG_CONFLICT";
    }
  } finally {
    if (!disposed) { state.busy = false; state.operation = ""; }
  }
}
/** 未保存内容只有明确放弃才清空。 */
async function allowDiscard() {
  if (state.busy || state.closing) return false;
  if (!dirty.value) return true;
  state.closing = true;
  try {
    return await confirmAction({
      title: "保留未保存设置",
      description: "当前编辑或运行选择尚未保存。放弃后将清空本次输入。",
      action: "放弃修改并继续",
    });
  } finally {
    state.closing = false;
  }
}
/** 重载失败保持当前副本，成功才采用数据库目录。 */
async function load() {
  if (!(await allowDiscard())) return;
  await run(async (signal) => {
    const catalog = await getModelConfiguration(signal);
    if (!disposed) adopt(catalog);
  });
}
/** 进入抽屉前处理未应用的运行选择，不同时维护两份待提交动作。 */
async function edit(kind, item) {
  if (state.conflict || !(await allowDiscard())) return;
  state.selection = { ...state.catalog.runtime };
  state.selectionBaseline = JSON.stringify(state.selection);
  state.kind = kind;
  state.id = item?.id || null;
  state.editor =
    kind === "platform"
      ? createPlatformEditor(item)
      : createModelEditor(item, state.catalog.platforms[0]?.id || "");
  state.baseline = JSON.stringify(state.editor);
  state.testResult = null;
  state.remoteModels = null;
  state.error = "";
}
/** 关闭抽屉、ESC 或点击遮罩都遵守相同保护规则。 */
async function closeEditor() {
  if (await allowDiscard()) clearEditor();
}
/** 保存不自动启用模型；成功关闭抽屉并刷新公开运行信息。 */
async function save() {
  if (!valid.value || state.conflict) return;
  await run(async (signal) => {
    const catalog =
      state.kind === "platform"
        ? await saveModelPlatform(
            state.id,
            platformPayload(state.editor, state.catalog.revision),
            signal,
          )
        : await saveModelProfile(
            state.id,
            modelConfigurationPayload(state.editor, state.catalog.revision),
            signal,
          );
    if (!disposed) {
      adopt(catalog);
      workspace.loadRuntime();
      toast.success("配置已保存");
    }
  });
}
/** 测试当前表单，不保存或启用；服务端负责隐藏上游敏感错误。 */
async function test() {
  if (!canTest.value || state.conflict) return;
  await run(async (signal) => {
    const result = await testModelConfiguration(
      state.id,
      modelConfigurationPayload(state.editor, state.catalog.revision),
      signal,
    );
    if (!disposed) state.testResult = result;
  }, "test");
}
/** 获取当前平台模型供选择；失败清空旧目录，成功不改变模型标识或持久化状态。 */
async function fetchModels() {
  if (!canFetchModels.value || state.conflict) return;
  state.remoteModels = null;
  await run(async (signal) => {
    const result = await fetchPlatformModels(state.editor.platformId,
      catalogFetchPayload(state.editor, state.catalog.revision, state.id), signal);
    if (!disposed) state.remoteModels = result.models;
  }, "catalog");
}
/** 已启用模型判断只读取当前保存目录。 */
function isActive(id) {
  return Boolean(
    id &&
      state.catalog?.runtime.mode === "ai" &&
      state.catalog.runtime.activeModelId === id,
  );
}
/** 平台关联数说明删除限制。 */
function modelCount(id) {
  return state.catalog.models.filter((m) => m.platformId === id).length;
}
/** 协议名称来自已支持的 SDK 契约。 */
function protocolLabel(protocol) {
  return modelProtocols.find((p) => p.value === protocol)?.label || protocol;
}
/** 删除需要明确确认，不在浏览器隐式级联清理模型。 */
async function remove(kind, item) {
  if (state.busy || state.editor || state.conflict || selectionDirty.value)
    return;
  const accepted = await confirmAction({
    title: `删除${kind === "platform" ? "接入平台" : "模型配置"}`,
    description: `确认删除“${item.name}”？${kind === "model" ? "该模型已保存的密钥也会删除。" : "此操作只删除该平台。"}`,
    action: "确认删除",
    destructive: true,
  });
  if (!accepted) return;
  await run(async (signal) => {
    const catalog =
      kind === "platform"
        ? await deleteModelPlatform(item.id, state.catalog.revision, signal)
        : await deleteModelProfile(item.id, state.catalog.revision, signal);
    if (!disposed) {
      adopt(catalog);
      workspace.loadRuntime();
      toast.success("配置已删除");
    }
  });
}
/** 应用运行选择只改变后续请求，不发起模型调用。 */
async function applySelection() {
  if (
    !canSelect.value ||
    !selectionDirty.value ||
    state.editor ||
    state.conflict
  )
    return;
  await run(async (signal) => {
    const catalog = await selectModel(
      {
        expectedRevision: state.catalog.revision,
        mode: state.selection.mode,
        activeModelId: state.selection.activeModelId || null,
      },
      signal,
    );
    if (!disposed) {
      adopt(catalog);
      workspace.loadRuntime();
      toast.success("运行设置已生效");
    }
  });
}
/** 导航确认后清空旧表单，避免平台/模型跨页串用。 */
async function allowLeave() {
  return allowDiscard();
}
/** 浏览器刷新保护编辑和正在执行的请求。 */
function beforeUnload(event) {
  if (dirty.value || state.busy) {
    event.preventDefault();
    event.returnValue = "";
  }
}
onBeforeRouteLeave(allowLeave);
onBeforeRouteUpdate(allowLeave);
watch(
  () => route.path,
  () => {
    clearEditor();
    state.search = "";
    if (state.catalog) {
      state.selection = { ...state.catalog.runtime };
      state.selectionBaseline = JSON.stringify(state.selection);
    }
    load();
  },
);
watch(
  () => state.editor,
  () => {
    state.testResult = null;
  },
  { deep: true },
);
/** 切换凭证或平台后旧列表不再可信；手动修改模型标识可继续使用同一列表。 */
watch(() => [state.editor?.platformId, state.editor?.apiKey, state.editor?.clearApiKey],
  () => { state.remoteModels = null; });
onMounted(() => {
  load();
  window.addEventListener("beforeunload", beforeUnload);
});
onBeforeUnmount(() => {
  disposed = true;
  controller?.abort();
  clearEditor();
  window.removeEventListener("beforeunload", beforeUnload);
});
</script>
<template>
  <section>
    <div class="page-heading">
      <div>
        <div class="eyebrow">SYSTEM SETTINGS</div>
        <h1>{{ isPlatformPage ? "接入平台" : "模型配置" }}</h1>
        <p>
          {{
            isPlatformPage
              ? "管理模型服务的接入地址、协议与模型列表地址。"
              : "维护模型与密钥，选择后续需求生成使用的模型。"
          }}
        </p>
      </div>
      <Button
        :disabled="
          state.busy ||
          state.conflict ||
          !state.catalog ||
          (!isPlatformPage && !state.catalog.platforms.length)
        "
        @click="edit(isPlatformPage ? 'platform' : 'model')"
        ><Plus class="size-4" />{{
          isPlatformPage ? "新增平台" : "新增模型"
        }}</Button
      >
    </div>
    <div
      v-if="state.error && !state.editor"
      class="inline-alert error"
      role="alert"
    >
      <AlertCircle />
      <div class="flex-1">
        {{ state.error }}
        <p v-if="state.conflict">目录已被其他窗口修改，请重新载入后继续。</p>
      </div>
      <Button variant="outline" size="sm" :disabled="state.busy" @click="load"
        >重新载入</Button
      >
    </div>
    <div v-if="state.busy && !state.catalog" class="empty-state">
      <Loader2 class="animate-spin" />
      <p>正在读取设置…</p>
    </div>
    <template v-if="state.catalog">
      <section v-if="!isPlatformPage" class="panel runtime-card">
        <div>
          <div class="flex items-center gap-2 mb-2">
            <span class="text-sm font-semibold">当前运行设置</span
            ><Badge variant="secondary">{{
              state.catalog.runtime.mode === "ai" ? "真实模型" : "本地联调"
            }}</Badge>
          </div>
          <p class="muted">
            {{
              state.catalog.runtime.mode === "ai"
                ? `${activeModel?.name || "模型不存在"} · ${platformNames[activeModel?.platformId] || ""}`
                : "使用本地模板生成草稿，不调用大模型。"
            }}
          </p>
          <div class="runtime-controls">
            <div class="field">
              <label for="runtime-mode">生成模式</label
              ><SelectField id="runtime-mode" v-model="state.selection.mode"
                :options="[{ value: 'demo', label: '本地模板联调' }, { value: 'ai', label: '真实模型' }]"
                :disabled="state.busy || Boolean(state.editor) || state.conflict" />
            </div>
            <div class="field">
              <label for="runtime-model">启用模型</label
              ><SelectField id="runtime-model" v-model="state.selection.activeModelId"
                :options="[{ value: null, label: '请选择模型' }, ...state.catalog.models.map(model => ({ value: model.id, label: `${model.name} · ${platformNames[model.platformId]}${!model.apiKeyConfigured ? '（未配置密钥）' : ''}`, disabled: !model.apiKeyConfigured }))]"
                :disabled="state.busy || Boolean(state.editor) || state.conflict || state.selection.mode === 'demo'" />
            </div>
            <Button
              :disabled="
                !selectionDirty ||
                !canSelect ||
                state.busy ||
                Boolean(state.editor) ||
                state.conflict
              "
              @click="applySelection"
              ><Check class="size-4" />应用设置</Button
            >
          </div>
          <p class="field-hint mt-3">
            保存模型与启用模型分别操作；应用设置不会发起模型调用。
          </p>
        </div>
        <div class="size-10 bg-muted rounded-xl grid place-items-center">
          <Bot class="size-5 text-muted-foreground" />
        </div>
      </section>
      <div class="panel">
        <div class="panel-heading">
          <div class="flex items-center gap-2">
            <h2>{{ isPlatformPage ? "平台目录" : "模型目录" }}</h2>
            <Badge variant="secondary">{{
              isPlatformPage
                ? state.catalog.platforms.length
                : state.catalog.models.length
            }}</Badge>
          </div>
          <div class="actions flex-1 justify-end">
            <div class="relative table-search">
              <Search
                class="absolute left-3 top-2.5 size-4 text-muted-foreground"
              /><Input
                v-model="state.search"
                class="pl-9"
                :aria-label="isPlatformPage ? '搜索平台' : '搜索模型'"
                :placeholder="
                  isPlatformPage ? '搜索平台或地址…' : '搜索模型、标识或平台…'
                "
              />
            </div>
            <Button
              variant="ghost"
              size="icon"
              aria-label="重新载入设置"
              :disabled="state.busy"
              @click="load"
              ><RefreshCw :class="['size-4', { 'animate-spin': state.busy }]"
            /></Button>
          </div>
        </div>
        <Table v-if="filteredItems.length"
          ><TableHeader
            ><TableRow
              ><TableHead>{{
                isPlatformPage ? "平台名称" : "模型名称"
              }}</TableHead
              ><TableHead>{{
                isPlatformPage ? "接入协议" : "所属平台 / 模型标识"
              }}</TableHead
              ><TableHead>{{
                isPlatformPage ? "接入地址" : "密钥状态"
              }}</TableHead
              ><TableHead>{{
                isPlatformPage ? "关联模型" : "运行状态"
              }}</TableHead
              ><TableHead class="text-right">操作</TableHead></TableRow
            ></TableHeader
          ><TableBody
            ><TableRow v-for="item in filteredItems" :key="item.id"
              ><TableCell class="py-5 pl-6"
                ><span class="row-title">{{ item.name }}</span></TableCell
              ><TableCell
                ><template v-if="isPlatformPage"
                  ><Badge variant="outline">{{
                    protocolLabel(item.protocol)
                  }}</Badge></template
                ><template v-else
                  ><span>{{ platformNames[item.platformId] }}</span>
                  <div class="row-subtitle font-mono">
                    {{ item.model }}
                  </div></template
                ></TableCell
              ><TableCell
                ><span
                  v-if="isPlatformPage"
                  class="font-mono text-xs text-muted-foreground"
                  >{{ item.baseUrl }}<span class="block mt-1 font-sans">{{ item.modelCatalogUrl ? '已配置模型列表地址' : '未配置模型列表地址' }}</span></span
                ><span
                  v-else
                  class="flex items-center gap-1.5 text-xs"
                  :class="
                    item.apiKeyConfigured
                      ? 'text-emerald-700'
                      : 'text-muted-foreground'
                  "
                  ><ShieldCheck
                    v-if="item.apiKeyConfigured"
                    class="size-3.5"
                  /><KeyRound v-else class="size-3.5" />{{
                    item.apiKeyConfigured ? "已加密保存" : "未配置"
                  }}</span
                ></TableCell
              ><TableCell
                ><span
                  v-if="isPlatformPage"
                  class="text-muted-foreground text-xs"
                  >{{ modelCount(item.id) }} 个模型</span
                ><Badge
                  v-else
                  :variant="isActive(item.id) ? 'default' : 'secondary'"
                  :class="
                    isActive(item.id) &&
                    'bg-emerald-50 text-emerald-700 border-emerald-200'
                  "
                  >{{ isActive(item.id) ? "当前启用" : "未启用" }}</Badge
                ></TableCell
              ><TableCell class="text-right pr-4"
                ><Button
                  variant="ghost"
                  size="sm"
                  :aria-label="`编辑${isPlatformPage ? '平台' : '模型'} ${item.name}`"
                  :disabled="state.busy || state.conflict"
                  @click="edit(isPlatformPage ? 'platform' : 'model', item)"
                  ><Pencil class="size-3.5" />编辑</Button
                ><Button
                  variant="ghost"
                  size="icon"
                  class="text-muted-foreground hover:text-destructive"
                  :aria-label="`删除${isPlatformPage ? '平台' : '模型'} ${item.name}`"
                  :title="
                    isPlatformPage && modelCount(item.id)
                      ? '有关联模型，无法删除'
                      : isActive(item.id)
                        ? '当前启用，需先切换运行设置'
                        : '删除配置'
                  "
                  :disabled="
                    state.busy ||
                    state.conflict ||
                    Boolean(state.editor) ||
                    selectionDirty ||
                    (isPlatformPage
                      ? modelCount(item.id) > 0
                      : isActive(item.id))
                  "
                  @click="remove(isPlatformPage ? 'platform' : 'model', item)"
                  ><Trash2
                    class="size-4" /></Button></TableCell></TableRow></TableBody
        ></Table>
        <div v-else class="empty-state">
          <Search v-if="state.search" /><Network
            v-else-if="isPlatformPage"
          /><Bot v-else />
          <p>
            {{
              state.search
                ? "没有匹配的配置，试试其他关键词。"
                : isPlatformPage
                  ? "暂无接入平台"
                  : "暂无模型配置"
            }}
          </p>
          <Button
            v-if="!state.search"
            variant="outline"
            :disabled="
              state.busy ||
              state.conflict ||
              (!isPlatformPage && !state.catalog.platforms.length)
            "
            @click="edit(isPlatformPage ? 'platform' : 'model')"
            >{{
              !isPlatformPage && !state.catalog.platforms.length
                ? "请先新增接入平台"
                : "创建第一项配置"
            }}</Button
          >
        </div>
      </div>
      <div class="flex justify-between items-center mt-4 field-hint">
        <span>{{
          isPlatformPage
            ? "有关联模型的平台需先移除模型后删除。"
            : "API Key 由后端加密保存，已有密钥不会返回页面。"
        }}</span
        ><span class="shrink-0 ml-4">目录 v{{ state.catalog.revision }}</span>
      </div>
    </template>
    <Sheet :open="Boolean(state.editor)" @update:open="!$event && closeEditor()"
      ><SheetContent class="w-full sm:max-w-[520px] gap-0 p-0 bg-white">
        <template v-if="state.editor"
          ><div class="sheet-header">
            <SheetTitle
              >{{ state.id ? "编辑" : "新增"
              }}{{
                state.kind === "platform" ? "接入平台" : "模型配置"
              }}</SheetTitle
            ><SheetDescription class="mt-2 leading-6">{{
              state.kind === "platform"
                ? "设置平台名称、接入协议、API 地址及可选模型列表地址。"
                : "选择平台并填写模型标识，密钥按需更新。"
            }}</SheetDescription>
          </div>
          <!-- 平台与模型字段属于系统配置；显式关闭自动填充，避免被识别为登录用户名。 -->
          <form id="catalog-form" class="sheet-body" autocomplete="off" @submit.prevent="save">
            <div v-if="state.error" class="inline-alert error" role="alert">
              <AlertCircle />
              <div>
                {{ state.error }}
                <p v-if="state.conflict" class="mt-2">
                  请保留输入，或重新载入最新目录后继续。
                </p>
                <Button
                  v-if="state.conflict"
                  type="button"
                  variant="outline"
                  size="sm"
                  class="mt-2"
                  :disabled="state.busy"
                  @click="load"
                  >放弃并重新载入</Button
                >
              </div>
            </div>
            <div class="field">
              <label for="catalog-name">{{
                state.kind === "platform" ? "平台名称" : "配置名称"
              }}</label
              ><Input
                id="catalog-name"
                autocomplete="off"
                v-model="state.editor.name"
                :maxlength="120"
                :disabled="state.busy"
                :placeholder="
                  state.kind === 'platform'
                    ? '例如：阿里云百炼'
                    : '例如：需求分析模型'
                "
              />
            </div>
            <template v-if="state.kind === 'platform'"
              ><div class="field">
                <label for="platform-protocol">接入协议</label
                ><SelectField id="platform-protocol" v-model="state.editor.protocol" :options="modelProtocols"
                  :disabled="state.busy || platformHasKeys" />
              </div>
              <div class="field">
                <label for="platform-base-url">接入地址</label
                ><Input
                  id="platform-base-url"
                  autocomplete="off"
                  v-model="state.editor.baseUrl"
                  :maxlength="500"
                  :disabled="state.busy || platformHasKeys"
                  placeholder="https://example.com/v1"
                />
                <p class="field-hint">
                  {{
                    state.editor.protocol === "dashscope"
                      ? "填写百炼服务根地址，不含 /api/v1。"
                      : "填写 API 基础路径，通常包含 /v1，不含 /chat/completions。"
                  }}
                </p>
              </div>
              <div class="field">
                <label for="platform-catalog-url">模型列表地址 <span class="text-muted-foreground font-normal">（可选）</span></label>
                <Input id="platform-catalog-url" autocomplete="off" v-model="state.editor.modelCatalogUrl"
                  :maxlength="500" :disabled="state.busy" placeholder="https://example.com/v1/models" />
                <p class="field-hint">填写返回 data[].id 的 GET 地址，须与接入地址同源。留空可关闭模型列表获取；保存平台不会自动访问此地址。</p>
              </div>
              <div v-if="platformHasKeys" class="inline-alert warning mt-6">
                <ShieldCheck />
                <p>
                  关联模型已保存密钥。如需更换协议或接入地址，请先清除这些模型的密钥；当前可以修改名称及同源模型列表地址。
                </p>
              </div></template
            >
            <template v-else
              ><div class="field">
                <label for="profile-platform">所属平台</label
                ><SelectField id="profile-platform" :model-value="state.editor.platformId"
                  :options="state.catalog.platforms.map(platform => ({ value: platform.id, label: platform.name }))"
                  :disabled="state.busy" @update:model-value="changeModelPlatform(state.editor, $event)" />
              </div>
              <div class="field">
                <label for="profile-model">模型标识</label
                ><Input
                  id="profile-model"
                  autocomplete="off"
                  v-model="state.editor.model"
                  :maxlength="120"
                  :disabled="state.busy"
                  placeholder="例如：qwen-plus"
                />
                <p class="field-hint">填写平台 API 接受的模型名称。</p>
              </div>
              <div class="field">
                <label for="profile-key">API Key</label
                ><Input
                  id="profile-key"
                  v-model="state.editor.apiKey"
                  type="password"
                  autocomplete="new-password"
                  :maxlength="4096"
                  :disabled="state.busy || state.editor.clearApiKey"
                  :placeholder="
                    previousModel?.apiKeyConfigured
                      ? '留空保留同平台的已有密钥'
                      : '输入平台 API Key'
                  "
                />
                <p class="field-hint">
                  {{
                    previousModel?.apiKeyConfigured
                      ? "已有密钥已加密保存，页面不回显。更换平台需要新密钥或明确清除。"
                      : "可先保存不含密钥的配置，测试和启用前再补充。"
                  }}
                </p>
              </div>
              <div class="flex items-start gap-2 mt-5">
                <Checkbox
                  id="clear-model-key"
                  v-model="state.editor.clearApiKey"
                  :disabled="
                    state.busy ||
                    Boolean(state.editor.apiKey) ||
                    editingActiveModel
                  "
                /><label for="clear-model-key" class="field-hint"
                  >清除已保存的密钥{{
                    editingActiveModel ? "（启用中的模型需先停用）" : ""
                  }}</label
                >
              </div>
              <div class="border-t mt-7 pt-5">
                <div class="flex items-center justify-between gap-3">
                  <span class="text-sm font-medium">平台模型列表</span>
                  <Button type="button" variant="outline" size="sm"
                    :disabled="!canFetchModels || state.busy || state.conflict" @click="fetchModels">
                    <Loader2 v-if="state.operation === 'catalog'" class="size-4 animate-spin" /><RefreshCw v-else class="size-4" />{{ state.operation === 'catalog' ? '获取中…' : state.remoteModels === null ? '获取模型列表' : '刷新列表' }}
                  </Button>
                </div>
                <p class="field-hint mt-3" v-if="!selectedPlatform?.modelCatalogUrl">请先在“接入平台”中配置并保存模型列表地址。模型标识也可手动填写。</p>
                <template v-else>
                  <p class="field-hint mt-3 break-all">{{ selectedPlatform.modelCatalogUrl }}</p>
                  <p class="field-hint mt-2">使用本次填写的密钥，留空时沿用当前模型同平台的已存密钥；没有密钥则匿名获取。仅获取目录，不发起推理。</p>
                </template>
                <SelectField v-if="state.remoteModels?.length" id="platform-model-choice" aria-label="选择平台模型" class="mt-3"
                  :model-value="state.remoteModels.includes(state.editor.model) ? state.editor.model : ''" :disabled="state.busy"
                  :options="state.remoteModels.map(model => ({ value: model, label: model }))" placeholder="选择模型，填入上方模型标识"
                  @update:model-value="state.editor.model = $event" />
                <p v-if="state.remoteModels !== null" class="field-hint mt-3" role="status">
                  {{ state.remoteModels.length ? `已获取 ${state.remoteModels.length} 个模型。选择后请保存配置，需要启用时再应用设置。` : '平台未返回本系统可用的模型标识，可继续手动填写。' }}
                </p>
              </div>
              <div class="border-t mt-7 pt-5">
                <div
                  v-if="state.testResult"
                  class="inline-alert success"
                  role="status"
                >
                  <Check />
                  <p>{{ state.testResult.message }}</p>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  :disabled="!canTest || state.busy || state.conflict"
                  @click="test"
                  ><Loader2
                    v-if="state.operation === 'test'"
                    class="size-4 animate-spin"
                  /><FlaskConical v-else class="size-4" />测试连接</Button
                >
                <p class="field-hint mt-3">
                  测试会调用平台，可能产生费用；不会保存表单或改变启用模型。
                </p>
              </div></template
            >
          </form>
          <div class="sheet-footer">
            <Button
              variant="outline"
              :disabled="state.busy"
              @click="closeEditor"
              >取消</Button
            ><Button
              type="submit"
              form="catalog-form"
              :disabled="!valid || state.busy || state.conflict"
              ><Loader2 v-if="state.busy" class="size-4 animate-spin" />保存{{
                state.kind === "platform" ? "平台" : "模型"
              }}</Button
            >
          </div></template
        >
      </SheetContent></Sheet
    >
  </section>
</template>
