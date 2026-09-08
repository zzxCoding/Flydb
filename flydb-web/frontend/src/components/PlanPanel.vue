<script setup lang="ts">
import { ref, computed, defineAsyncComponent, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Run } from '../types'
import Icon from './Icon.vue'
import Pagination from './Pagination.vue'
const props = defineProps<{ run: Run; busy: boolean }>()
const emit = defineEmits<{ close: []; execute: [] }>()
const { t } = useI18n()
const selected = ref(0)
const scripts = computed(() => props.run.detailsOmitted ? [] : props.run.result?.migrations ?? [])
const script = computed(() => scripts.value[selected.value])
const SqlPreview = defineAsyncComponent(() => import('./SqlPreview.vue').then(module => module.default))
const scriptPage = ref(0), scriptQuery = ref(''), pageSize = 50
const matchingScripts = computed(() => {
  const query = scriptQuery.value.trim().toLowerCase()
  return scripts.value.map((item, index) => ({ item, index })).filter(({ item }) => !query || `${item.version ?? ''} ${item.script}`.toLowerCase().includes(query))
})
const visibleScripts = computed(() => matchingScripts.value.slice(scriptPage.value * pageSize, (scriptPage.value + 1) * pageSize))
watch(scriptQuery, () => { scriptPage.value = 0 })
watch(() => matchingScripts.value.length, count => { scriptPage.value = Math.min(scriptPage.value, Math.max(0, Math.ceil(count / pageSize) - 1)) })
const sqlText = computed(() => (script.value?.statements ?? []).map(s => `-- ${t('line', { line: s.lineNumber })}\n${s.sql};`).join('\n\n'))
function download() {
  const url = URL.createObjectURL(new Blob([sqlText.value], { type: 'text/plain;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = script.value?.script.split(/[\\/]/).pop() || 'preview.sql'; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>
<template><article class="plan-panel">
  <header class="dialog-header"><div><span class="eyebrow">{{ t(run.command === 'undo-plan' ? 'undo-plan' : 'plan') }}</span><h2>{{ t('previewTitle') }}</h2></div><button class="icon-button" :aria-label="t('close')" @click="emit('close')"><Icon name="close" /></button></header>
  <p class="muted">{{ t('previewBody') }}</p><div class="plan-target"><span>{{ t('target') }}</span><strong>{{ run.profileName }}</strong><code>{{ run.target }}</code></div>
  <div class="plan-counts"><span>{{ t('migrationCount', { count: run.result?.plan?.migrationCount ?? 0 }) }}</span><span>{{ t('statementCount', { count: run.result?.plan?.statementCount ?? 0 }) }}</span></div>
  <div v-if="scripts.length" class="plan-split"><div class="plan-files"><input v-model="scriptQuery" class="migration-search" type="search" :aria-label="t('searchMigrations')" :placeholder="t('searchMigrations')"><nav :aria-label="t('migrations')"><button v-for="{ item, index } in visibleScripts" :key="item.script" :aria-current="selected === index ? 'true' : undefined" @click="selected = index"><span class="version-tag">{{ item.version ?? 'R' }}</span><span>{{ item.script }}<small>{{ t('statementCount', { count: item.statementCount ?? 0 }) }}</small></span></button></nav><Pagination v-model="scriptPage" :total="matchingScripts.length" :size="pageSize" /></div><section class="sql-view" :aria-label="t('sqlPreview')"><h3>{{ script?.script }}</h3><div class="sql-tools"><span>{{ t('sqlSearchHint') }}</span><button class="text-button" @click="download">{{ t('downloadSql') }}</button></div><SqlPreview :text="sqlText" :label="t('sqlPreview')" /></section></div>
  <p v-else class="empty-small">{{ t(run.detailsOmitted ? 'loadingRun' : 'noPending') }}</p>
  <details class="plan-digest"><summary>{{ t('planId') }}</summary><code>{{ run.result?.plan?.id }}</code></details>
  <p class="muted small">{{ t('previewLimit') }}</p>
  <footer class="dialog-actions"><button class="button" @click="emit('close')">{{ t('cancel') }}</button><button class="button primary" :disabled="busy || !scripts.length || !run.result?.planId" @click="emit('execute')"><Icon name="play" />{{ t(run.command === 'undo-plan' ? 'confirmUndo' : 'confirmMigrate') }}</button></footer>
</article></template>
