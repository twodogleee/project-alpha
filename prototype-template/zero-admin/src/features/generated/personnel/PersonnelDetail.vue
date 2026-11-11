<script setup>
/** 人员只读详情与列表共用同一模拟实体，缺失记录显示空态。@author Andersen */
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { CrudDetail, CrudDetailItem, AsyncState } from '@/platform/components.js';
import { findPerson, departmentName } from '@/mock/generated/personnel-store.js';
/** 路由只传记录标识。 */
const route = useRoute();
/** 返回业务列表的导航实例。 */
const router = useRouter();
/** 从共享存储读取实时实体，不能复制为另一个数据真值。 */
const person = computed(() => findPerson(String(route.params.id)));
</script>

<template>
  <section class="prototype-page" data-testid="detail-page">
    <div class="prototype-page-title"><div><h1>人员详情</h1><p>查看本次演示中的人员资料。</p></div><el-button data-testid="back-list" @click="router.push('/personnel')">返回人员列表</el-button></div>
    <AsyncState :empty="!person" empty-text="未找到该人员，记录可能已重置">
      <article class="prototype-detail-card"><h2 data-testid="detail-name">{{ person?.name }}</h2><CrudDetail :columns="2"><CrudDetailItem label="人员姓名" :value="person?.name" /><CrudDetailItem label="所属部门" :value="departmentName(person?.departmentId)" /><CrudDetailItem label="邮箱" :value="person?.email" /><CrudDetailItem label="资源容量" :value="person?.capacity" /><CrudDetailItem label="启用状态" :value="person?.enabled ? '启用' : '停用'" /><CrudDetailItem label="人员标识" :value="person?.id" /><CrudDetailItem label="备注" :value="person?.notes" span="full" /></CrudDetail><p class="prototype-detail-meta">此处为只读详情。所有记录均为模拟数据，不连接真实业务系统。</p></article>
    </AsyncState>
  </section>
</template>
