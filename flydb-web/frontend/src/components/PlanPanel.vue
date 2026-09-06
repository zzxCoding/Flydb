<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Run } from '../types'
import Icon from './Icon.vue'
const props = defineProps<{ run: Run; busy: boolean }>()
const emit = defineEmits<{ close: []; execute: [] }>()
const { t } = useI18n()
const selected = ref(0)
const scripts = computed(() => props.run.result?.migrations ?? [])
const script = computed(() => scripts.value[selected.value])
</script>
<template><article class="plan-panel">
  <header class="dialog-header"><div><span class="eyebrow">{{ t(run.command === 'undo-plan' ? 'undo-plan' : 'plan') }}</span><h2>{{ t('previewTitle') }}</h2></div><button class="icon-button" :aria-label="t('close')" @click="emit('close')"><Icon name="close" /></button></header>
  <p class="muted">{{ t('previewBody') }}</p><div class="plan-target"><span>{{ t('target') }}</span><strong>{{ run.profileName }}</strong><code>{{ run.target }}</code></div>
  <div class="plan-counts"><span>{{ t('migrationCount', { count: run.result?.plan?.migrationCount ?? 0 }) }}</span><span>{{ t('statementCount', { count: run.result?.plan?.statementCount ?? 0 }) }}</span></div>
  <div v-if="scripts.length" class="plan-split"><nav :aria-label="t('migrations')"><button v-for="(item, index) in scripts" :key="item.script" :aria-current="selected === index ? 'true' : undefined" @click="selected = index"><span class="version-tag">{{ item.version ?? 'R' }}</span><span>{{ item.script }}<small>{{ t('statementCount', { count: item.statementCount ?? 0 }) }}</small></span></button></nav><section class="sql-view" :aria-label="t('sqlPreview')"><h3>{{ script?.script }}</h3><div v-for="(statement, index) in script?.statements" :key="index" class="statement"><span>{{ t('line', { line: statement.lineNumber }) }}</span><pre><code>{{ statement.sql }};</code></pre></div></section></div>
  <p v-else class="empty-small">{{ t('noPending') }}</p>
  <details class="plan-digest"><summary>{{ t('planId') }}</summary><code>{{ run.result?.plan?.id }}</code></details>
  <p class="muted small">{{ t('previewLimit') }}</p>
  <footer class="dialog-actions"><button class="button" @click="emit('close')">{{ t('cancel') }}</button><button class="button primary" :disabled="busy || !scripts.length || !run.result?.planId" @click="emit('execute')"><Icon name="play" />{{ t(run.command === 'undo-plan' ? 'confirmUndo' : 'confirmMigrate') }}</button></footer>
</article></template>
