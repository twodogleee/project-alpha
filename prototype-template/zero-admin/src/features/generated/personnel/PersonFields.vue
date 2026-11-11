<script setup>
/** 人员样板受控字段；父页面持有独立表单，不直接修改实体。@author Andersen */
import { departments } from '@/mock/generated/personnel-store.js';
/** 父页面独立表单值。 */
const props = defineProps({ modelValue: { type: Object, required: true } });
/** 每个字段变更以新对象通知父页面。 */
const emit = defineEmits(['update:modelValue']);
/** 更新指定字段，保留其他尚未保存输入。 */
function update(field, value) { emit('update:modelValue', { ...props.modelValue, [field]: value }); }
</script>

<template>
  <el-form :model="modelValue" label-position="top" @submit.prevent>
    <el-form-item label="人员姓名" required><el-input data-testid="person-name" :model-value="modelValue.name" maxlength="80" placeholder="请输入人员姓名" @update:model-value="update('name', $event)" /></el-form-item>
    <el-form-item label="所属部门" required><el-select data-testid="person-department" :model-value="modelValue.departmentId" class="w-full" @update:model-value="update('departmentId', $event)"><el-option v-for="item in departments" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
    <el-form-item label="邮箱"><el-input data-testid="person-email" :model-value="modelValue.email" placeholder="name@example.test" @update:model-value="update('email', $event)" /></el-form-item>
    <el-form-item label="资源容量"><el-input-number data-testid="person-capacity" :model-value="modelValue.capacity" :min="0" :max="100" :precision="0" @update:model-value="update('capacity', $event)" /><span class="ml-3 text-xs text-gray-500">0 表示当前不承接任务</span></el-form-item>
    <el-form-item label="启用状态"><el-switch data-testid="person-status" :model-value="modelValue.enabled" active-text="启用" inactive-text="停用" @update:model-value="update('enabled', $event)" /></el-form-item>
    <el-form-item label="备注"><el-input data-testid="person-notes" :model-value="modelValue.notes" type="textarea" :rows="3" maxlength="500" @update:model-value="update('notes', $event)" /></el-form-item>
  </el-form>
</template>
