<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, defineAsyncComponent } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Run } from '../types'
import Status from './Status.vue'
import Icon from './Icon.vue'
const props = defineProps<{ run: Run }>()
const emit = defineEmits<{ close: [] }>()
const { t, te, locale } = useI18n()
const now = ref(Date.now()), copied = ref(false)
const rawOpen = ref(false)
const SqlPreview = defineAsyncComponent(() => import('./SqlPreview.vue').then(module => module.default))
const unreadable = computed(() => props.run.recovery === 'UNREADABLE_RECORD')
const command = computed(() => props.run.command ? (te(props.run.command) ? t(props.run.command) : props.run.command) : '—')
const verification = computed(() => props.run.verification || 'UNAVAILABLE')
let timer: ReturnType<typeof setInterval>
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 1000) }); onUnmounted(() => clearInterval(timer))
const duration = computed(() => {
  const run = props.run
  if (!run.startedAt) return '—'
  const end = run.endedAt ? Date.parse(run.endedAt) : run.status === 'RUNNING' ? now.value : NaN
  const ms = end - Date.parse(run.startedAt)
  if (!Number.isFinite(ms) || ms < 0) return '—'
  const seconds = Math.max(0, Math.floor(ms / 1000)); return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
})
function driverSource(source: string) {
  const labels: Record<string, string> = {'drivers 目录':'directory','运行时 classpath':'classpath','Maven 本地仓库':'local','Flydb 驱动缓存':'cache','Maven 中央仓库':'central'}
  if (labels[source]) return t('driverSources.' + labels[source])
  if (source.startsWith('Maven 私服 ')) return t('driverSources.remote') + ' · ' + source.slice(9)
  return source
}
function report() {
  const r = props.run
  const state = (v: string) => te(`states.${v}`) ? t(`states.${v}`) : v
  return [
    t('exportTitle'), `${t('reportZone')}: ${locale.value} · ${Intl.DateTimeFormat().resolvedOptions().timeZone}`,
    new Date().toLocaleString(locale.value), '',
    `${t('name')}: ${r.profileName ?? r.configPath ?? 'Flydb'}`,
    `${t('target')}: ${r.target ?? '—'}`, `${t('execution')}: ${state(r.status)}`,
    `${t('verification')}: ${state(verification.value)}`, `${t('elapsed')}: ${duration.value}`,
    unreadable.value ? t('unreadableRecord') : '',
    r.result?.error ? `${t('operationFailed')}: ${te('errors.' + r.result.error.code) ? t('errors.' + r.result.error.code) : r.result.error.code}` : '',
    '', t('details'), JSON.stringify(r, null, 2),
  ].join('\n')
}
async function copy() { try { await navigator.clipboard.writeText(report()); copied.value = true } catch { download() } }
function download() { const blob = new Blob([report()], { type: 'text/plain;charset=utf-8' }); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = `flydb-${props.run.id}-${locale.value}.txt`; a.click(); URL.revokeObjectURL(url) }
</script>
<template><article class="run-panel">
  <header class="dialog-header"><div><span class="eyebrow">{{ run.source ?? '—' }} · {{ command }}</span><h2>{{ run.profileName ?? run.configPath ?? 'Flydb' }}</h2></div><button class="icon-button" :aria-label="t('close')" @click="emit('close')"><Icon name="close" /></button></header>
  <div class="run-state"><Status :value="run.status" /><span class="elapsed">{{ t('elapsed') }} <strong>{{ duration }}</strong></span></div>
  <p class="target-line"><Icon name="database" /><code>{{ run.target ?? run.configPath ?? '—' }}</code></p>
  <div v-if="run.status === 'RUNNING'" class="notice"><span class="activity-dot" /><div>{{ t('runningBody') }}</div></div>
  <section v-if="run.progress" class="progress-section"><div class="section-heading"><h3>{{ t('jdbcConfirmed') }}</h3><span class="mono">{{ run.progress.confirmed }} / {{ run.progress.total }}</span></div><div class="progress-track" role="progressbar" :aria-label="t('jdbcConfirmed')" :aria-valuenow="run.progress.confirmed" :aria-valuemin="0" :aria-valuemax="run.progress.total || 1"><span :style="{ width: `${run.progress.total ? run.progress.confirmed / run.progress.total * 100 : 0}%` }" /></div><code>{{ run.progress.script }}</code><p class="muted">{{ t('commitNote') }}</p></section>
  <p v-if="run.lastActivityAt" class="muted small">{{ t('lastActivity') }} · {{ new Date(run.lastActivityAt).toLocaleString(locale) }}</p>
  <p v-if="unreadable" class="notice warning">{{ t('unreadableRecord') }}</p>
  <p v-else-if="run.status === 'UNKNOWN'" class="notice warning">{{ t('recoveryHint') }}</p>
  <p v-if="run.status === 'FAILED'" class="notice warning">{{ t('failureHint') }}</p>
  <div class="run-outcomes"><div><span>{{ t('execution') }}</span><Status :value="run.status" /></div><div><span>{{ t('verification') }}</span><Status :value="verification" /></div><div v-if="run.transactionResult"><span>{{ t('details') }}</span><strong>{{ t(`transactions.${run.transactionResult.transaction}`) }}</strong></div></div>
  <div v-if="run.result?.error" class="notice error"><Icon name="alert" /><div><strong>{{ run.result.error.code }}</strong><p>{{ te(`errors.${run.result.error.code}`) ? t(`errors.${run.result.error.code}`) : t('operationFailed') }}</p><p v-if="run.result.error.detail" class="error-detail">{{ run.result.error.detail }}</p></div></div>
  <div v-if="run.driver" class="driver-result"><span>{{ t('driverSource') }}</span><code>{{ run.driver.className }}</code><code>{{ driverSource(run.driver.source) }}</code></div>
  <p v-if="run.detailsOmitted" class="notice">{{ t('loadingRun') }}</p>
  <details v-if="run.result && !run.detailsOmitted" class="settings-section" @toggle="rawOpen = ($event.target as HTMLDetailsElement).open"><summary>{{ t('details') }}<Icon name="chevron" /></summary><div v-if="rawOpen" class="run-json"><SqlPreview :text="JSON.stringify(run.result, null, 2)" :label="t('details')" /></div></details>
  <footer class="dialog-actions"><button class="button" :disabled="run.detailsOmitted" @click="copy"><Icon name="copy" />{{ t(copied ? 'copied' : 'copy') }}</button><button class="button" :disabled="run.detailsOmitted" @click="download"><Icon name="download" />{{ t('export') }}</button></footer>
</article></template>
