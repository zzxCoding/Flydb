<script setup lang="ts">
import { ref, shallowRef, computed, watch, onMounted, onUnmounted, type Ref } from 'vue'
import { NConfigProvider, NModal, darkTheme } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import { api, connect, ApiError } from './api'
import { explainError } from './errors'
import type { Bootstrap, Profile, ConfigSnapshot, Run, CommandResult } from './types'
import Brand from './components/Brand.vue'
import Icon from './components/Icon.vue'
import Status from './components/Status.vue'
import ConnectionEditor from './components/ConnectionEditor.vue'
import ProfileDialog from './components/ProfileDialog.vue'
import PlanPanel from './components/PlanPanel.vue'
import RunPanel from './components/RunPanel.vue'
import Pagination from './components/Pagination.vue'
import ProfileSidebar from './components/ProfileSidebar.vue'
import AgentHandoff from './components/AgentHandoff.vue'
import CleanDialog from './components/CleanDialog.vue'

const { t, te, locale } = useI18n()
const dark = ref(localStorage.getItem('flydb.theme') !== 'light')
const data = shallowRef<Bootstrap>(), selected = ref(localStorage.getItem('flydb.selected') ?? ''), config = ref<ConfigSnapshot>()
const tab = ref<'migrations' | 'connection' | 'history'>('migrations'), sidebar = ref(false)
const serviceError = ref(''), actionError = ref(''), loading = ref(true), submitting = ref(false), dirty = ref(false)
const profileModal = ref(false), profileMode = ref<'add' | 'edit' | 'duplicate'>('add'), menuOpen = ref(false)
const allHistory = ref(false)
const profileDialog = ref<InstanceType<typeof ProfileDialog>>()
const activeRun = ref<string>(), previewRun = ref<string>(), openWhenDone = ref<string>()
const temporaryPasswords = ref<Record<string, string>>({}), discardTarget = ref<string>(), advanced = ref<'baseline' | 'repair' | 'remove' | 'clean'>(), baselineVersion = ref('1')
let polling: ReturnType<typeof setInterval>, updates: EventSource | undefined, fetching = false

const profile = computed(() => data.value?.profiles.find(p => p.id === selected.value))
const runs = computed(() => (data.value?.runs ?? []).filter(run => !profile.value || run.profileId === profile.value.id || run.configPath === profile.value.configPath))
const historyPage = ref(0), allHistoryPage = ref(0), historyPageSize = 10
const allRuns = computed(() => data.value?.runs ?? [])
const visibleRuns = computed(() => runs.value.slice(historyPage.value * historyPageSize, (historyPage.value + 1) * historyPageSize))
const visibleAllRuns = computed(() => allRuns.value.slice(allHistoryPage.value * historyPageSize, (allHistoryPage.value + 1) * historyPageSize))
watch(selected, () => { historyPage.value = 0 })
watch(() => runs.value.length, count => { historyPage.value = Math.min(historyPage.value, Math.max(0, Math.ceil(count / historyPageSize) - 1)) })
watch(() => allRuns.value.length, count => { allHistoryPage.value = Math.min(allHistoryPage.value, Math.max(0, Math.ceil(count / historyPageSize) - 1)) })
const running = computed(() => runs.value.find(run => run.status === 'RUNNING'))
const inspectionRun = computed(() => runs.value.find(run => run.status === 'SUCCEEDED' && (['inspect', 'info'].includes(run.command ?? '') || run.result?.inspection)))
const inspection = computed(() => inspectionRun.value?.result?.inspection ?? inspectionRun.value?.result)
const migrations = computed(() => inspection.value?.migrations ?? [])
const migrationQuery = ref(''), migrationPage = ref(0), migrationPageSize = 10
const matchingMigrations = computed(() => {
  const value = migrationQuery.value.trim().toLowerCase()
  return value ? migrations.value.filter(m => `${m.version ?? ''} ${m.script} ${m.description ?? ''}`.toLowerCase().includes(value)) : migrations.value
})
const visibleMigrations = computed(() => matchingMigrations.value.slice(migrationPage.value * migrationPageSize, (migrationPage.value + 1) * migrationPageSize))
watch([selected, migrationQuery], () => { migrationPage.value = 0 })
watch(() => matchingMigrations.value.length, count => { migrationPage.value = Math.min(migrationPage.value, Math.max(0, Math.ceil(count / migrationPageSize) - 1)) })
const pending = computed(() => migrations.value.filter(m => ['PENDING', 'OUT_OF_ORDER', 'OUTDATED'].includes(m.state ?? '')).length)
const applied = computed(() => migrations.value.filter(m => ['SUCCESS', 'BASELINE', 'MISSING', 'FUTURE', 'OUTDATED'].includes(m.state ?? '')).length)
const historyMismatch = computed(() => migrations.value.some(m => ['MISSING', 'FUTURE'].includes(m.state ?? '')))
function detail(id: Ref<string | undefined>) {
  const loaded = shallowRef<Run>(), error = ref(''), retry = ref(0)
  const summary = computed(() => data.value?.runs.find(run => run.id === id.value))
  watch(() => `${summary.value?.id}:${summary.value?.sequence}:${summary.value?.status}:${retry.value}`, async (_, __, cleanup) => {
    let cancelled = false; cleanup(() => { cancelled = true })
    loaded.value = undefined; error.value = ''
    const current = summary.value
    if (!current?.detailsOmitted) return
    try { const full = await api<Run>(`/runs/${current.id}`); if (!cancelled) loaded.value = full }
    catch (e) { if (!cancelled) error.value = explainError(e, t, te) }
  })
  return { run: computed(() => loaded.value ?? summary.value), error, retry }
}
const runDetail = detail(activeRun), planDetail = detail(previewRun)
const viewedRun = runDetail.run, viewedPlan = planDetail.run
const commandName = (value?: string) => value ? (te(value) ? t(value) : value) : '—'
const date = (value?: string) => value && Number.isFinite(Date.parse(value)) ? new Date(value).toLocaleString(locale.value, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—'

watch(dark, value => { document.documentElement.dataset.theme = value ? 'dark' : 'light'; localStorage.setItem('flydb.theme', value ? 'dark' : 'light') }, { immediate: true })
watch(locale, value => { localStorage.setItem('flydb.language', value); document.documentElement.lang = value }, { immediate: true })
watch(selected, value => { localStorage.setItem('flydb.selected', value); config.value = undefined; dirty.value = false; refreshConfig() })

async function refreshConfig() {
  const id = selected.value
  if (!id || !data.value?.profiles.some(p => p.id === id)) return
  try {
    const snapshot = await api<ConfigSnapshot>(`/profiles/${id}/config`)
    if (id === selected.value) config.value = snapshot
  } catch (e) { if (id === selected.value) actionError.value = explainError(e, t, te) }
}
async function refresh() {
  if (fetching) return
  fetching = true
  try {
    data.value = await api<Bootstrap>('/bootstrap?compact=true'); serviceError.value = ''
    if (!data.value.profiles.some(p => p.id === selected.value)) selected.value = data.value.profiles[0]?.id ?? ''
    if (openWhenDone.value) {
      const completed = data.value.runs.find(run => run.id === openWhenDone.value && run.status !== 'RUNNING')
      if (completed) {
        openWhenDone.value = undefined
        if (completed.status === 'SUCCEEDED' && ['plan', 'undo-plan'].includes(completed.command ?? '')) previewRun.value = completed.id
        else if (completed.command === 'validate' || completed.status !== 'SUCCEEDED') activeRun.value = completed.id
        else activeRun.value = completed.id
      }
    }
    await refreshConfig()
  } catch (e) { serviceError.value = String(e) }
  finally { loading.value = false; fetching = false }
}
async function start() { loading.value = true; try { await connect(); await refresh() } catch (e) { serviceError.value = String(e); loading.value = false } }
function choose(id: string) { allHistory.value = false; sidebar.value = false; if (id === selected.value) return; if (dirty.value) discardTarget.value = id; else selected.value = id }
function showProfile(mode: 'add' | 'edit' | 'duplicate') { profileMode.value = mode; profileModal.value = true; menuOpen.value = false }
async function profileSaved(profiles: Profile[]) { profileModal.value = false; await refresh(); if (profiles[0]) choose(profiles[0].id) }

async function action(command: string, extra: Record<string, unknown> = {}) {
  if (!profile.value || submitting.value || running.value) return
  submitting.value = true; actionError.value = ''
  try {
    const run = await api<Run>(`/profiles/${profile.value.id}/actions`, {
      command, revision: config.value?.revision, password: temporaryPasswords.value[selected.value] || undefined,
      requestId: crypto.randomUUID(), ...extra,
    })
    if (['plan', 'undo-plan', 'inspect', 'validate'].includes(command)) openWhenDone.value = run.id
    else if (['migrate', 'undo', 'baseline', 'repair', 'clean'].includes(command)) activeRun.value = run.id
    updates?.close(); const stream = new EventSource(`/api/runs/${run.id}/events`)
    updates = stream; stream.onmessage = () => { refresh() }; stream.addEventListener('finished', () => { stream.close(); refresh() })
    previewRun.value = undefined; advanced.value = undefined; await refresh()
  } catch (e) { actionError.value = e instanceof ApiError && te(`errors.${e.code}`) ? t(`errors.${e.code}`) : String(e) }
  finally { submitting.value = false }
}
async function confirmAdvanced() {
  if (advanced.value === 'remove' && profile.value) {
    try { await api(`/profiles/${profile.value.id}`, {}, 'DELETE'); advanced.value = undefined; await refresh() }
    catch (e) { actionError.value = explainError(e, t, te) }
  } else if (advanced.value && advanced.value !== 'clean') await action(advanced.value, { baselineVersion: baselineVersion.value, confirmed: true })
}
function beforeUnload(e: BeforeUnloadEvent) { if (dirty.value) { e.preventDefault(); e.returnValue = '' } }
onMounted(() => { start(); polling = setInterval(refresh, 5000); window.addEventListener('beforeunload', beforeUnload); window.addEventListener('hashchange', start) })
onUnmounted(() => { clearInterval(polling); updates?.close(); window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('hashchange', start) })
</script>

<template>
<NConfigProvider :theme="dark ? darkTheme : null">
  <div class="workbench-shell">
    <aside class="sidebar" :class="{ visible: sidebar }">
      <header class="sidebar-brand"><Brand /><button class="icon-button mobile-only" :aria-label="t('close')" @click="sidebar = false"><Icon name="close" /></button></header>
      <div class="workspace-label"><span class="activity-dot"></span>{{ t('workbench') }}<span class="mono">{{ data?.version }}</span></div>
      <div class="sidebar-section-heading"><h2>{{ t('configurations') }}</h2><button class="icon-button" :aria-label="t('addConfig')" @click="showProfile('add')"><Icon name="plus" /></button></div>
      <ProfileSidebar :profiles="data?.profiles ?? []" :groups="data?.groups ?? []" :runs="data?.runs ?? []" :selected="selected" :state-directory="data?.stateDirectory ?? ''" @choose="choose" @changed="refresh" />
      <button class="all-runs-button" @click="allHistory = true; sidebar = false"><Icon name="history" :size="17" />{{ t('allRuns') }}</button>
      <footer class="sidebar-footer"><span class="sidebar-caption">Flydb · {{ t('noLogin') }}</span><div class="preferences"><button class="button compact" :aria-label="t('language')" @click="locale = locale === 'zh-CN' ? 'en' : 'zh-CN'"><Icon name="globe" :size="16" />{{ locale === 'zh-CN' ? 'EN' : '中文' }}</button><button class="icon-button" :aria-label="t('theme')" @click="dark = !dark"><Icon :name="dark ? 'sun' : 'moon'" /></button></div></footer>
    </aside>
    <button v-if="sidebar" class="sidebar-backdrop" :aria-label="t('close')" @click="sidebar = false"></button>
    <main class="main-workspace" :inert="sidebar || undefined">
      <header class="topbar"><div class="breadcrumbs"><button class="icon-button mobile-only" :aria-label="t('menu')" :aria-expanded="sidebar" @click="sidebar = true"><Icon name="menu" /></button><Icon name="folder" :size="16" /><span>{{ t('configurations') }}</span><template v-if="profile"><span class="slash">/</span><strong>{{ profile.name }}</strong></template></div><div class="topbar-actions"><span v-if="dirty" class="draft-indicator">{{ t('unsaved') }}</span><button class="button compact" @click="showProfile('add')"><Icon name="plus" :size="16" />{{ t('addConfig') }}</button></div></header>
      <div v-if="serviceError && !data" class="service-error"><Icon name="alert" :size="32" /><h1>{{ t('serviceUnavailable') }}</h1><p>{{ t('serviceHelp') }}</p><button class="button primary" @click="start">{{ t('retry') }}</button><details><summary>{{ t('details') }}</summary><pre>{{ serviceError }}</pre></details></div>
      <div v-else-if="loading && !data" class="loading-state" aria-busy="true"><Brand /><span class="loading-line"></span></div>
      <section v-else-if="!profile" class="welcome">
        <div class="welcome-orbit" aria-hidden="true"><div class="orbit-ring"></div><div class="orbit-ring second"></div><span class="orbital-point"></span><span class="orbital-point second"></span><Brand /></div>
        <span class="eyebrow">{{ t('firstStep') }}</span><h1>{{ t('welcomeTitle') }}</h1><p>{{ t('welcomeBody') }}</p><div class="welcome-actions"><button class="button primary" @click="showProfile('add')"><Icon name="folder" />{{ t('import') }}<Icon name="arrow" /></button></div><small>{{ t('welcomeNote') }}</small>
      </section>
      <div v-else class="workspace-content">
        <div v-if="serviceError" class="notice warning" role="alert"><Icon name="alert" /><div><p>{{ t('serviceUnavailable') }}</p><small>{{ t('serviceDraftRetained') }}</small></div><button class="button compact" :disabled="loading" @click="start">{{ t('retry') }}</button></div>
        <header class="profile-heading"><div><div class="profile-eyebrow"><span class="eyebrow">{{ profile.group || 'FLYDB' }}</span><span v-if="profile.environment" class="environment-tag">{{ profile.environment }}</span></div><h1>{{ profile.name }}</h1><p class="profile-target"><Icon name="database" :size="16" /><code :title="config?.effective['flydb.url'] || profile.configPath">{{ config?.effective['flydb.url'] || config?.values['flydb.url'] || profile.configPath }}</code></p></div><div class="profile-options"><AgentHandoff :profile="profile" :config="config" :runs="runs" :version="data?.version ?? ''" :state-directory="data?.stateDirectory ?? ''" :dirty="dirty" :offline="!!serviceError" /><button class="icon-button" :aria-label="t('advanced')" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen"><Icon name="more" :size="22" /></button><div v-if="menuOpen" class="popover-menu"><button @click="showProfile('edit')">{{ t('rename') }}</button><button @click="showProfile('duplicate')">{{ t('duplicate') }}</button><button @click="advanced = 'remove'; menuOpen = false">{{ t('remove') }}</button></div></div></header>
        <div v-if="actionError || config?.error" class="notice error" role="alert"><Icon name="alert" /><div><p>{{ actionError || (config?.error && te('errors.' + config.error.code) ? t('errors.' + config.error.code) : config?.error?.detail) }}</p><small v-if="!actionError && config?.error?.detail">{{ config.error.detail }}</small></div><button v-if="actionError" class="icon-button" :aria-label="t('close')" @click="actionError = ''"><Icon name="close" /></button></div>
        <div v-if="running" class="running-strip"><span class="activity-dot" /><span>{{ t('running') }} · {{ running.script || commandName(running.command) }}</span><button class="text-button" @click="activeRun = running.id">{{ t('viewRun') }}<Icon name="arrow" :size="15" /></button></div>
        <nav class="content-tabs" :aria-label="t('workbench')"><button v-for="item in (['migrations', 'connection', 'history'] as const)" :key="item" :aria-current="tab === item ? 'page' : undefined" @click="tab = item"><Icon :name="item === 'migrations' ? 'database' : item === 'connection' ? 'settings' : 'history'" :size="16" />{{ t(item) }}<span v-if="item === 'history' && runs.length" class="tab-count">{{ runs.length }}</span></button></nav>
        <section v-if="tab === 'migrations'" class="migration-view">
          <div class="migration-toolbar"><div><h2>{{ t('migrations') }}</h2><span class="muted small">{{ inspectionRun ? `${t('lastChecked')} ${date(inspectionRun.endedAt)}` : t('neverChecked') }}</span></div><div class="actions"><button class="button" :disabled="submitting || !!running" @click="action('inspect')"><Icon name="refresh" />{{ t('refresh') }}</button><button class="button primary" :disabled="submitting || !!running || dirty" @click="action('plan')"><Icon name="play" :size="15" />{{ t('plan') }}</button></div></div>
          <p v-if="inspectionRun && inspectionRun.configRevision !== config?.revision" class="notice warning">{{ t('stale') }}</p>
          <div v-if="historyMismatch" class="notice warning history-mismatch" role="status"><Icon name="alert" /><div><strong>{{ t('historyMismatchTitle') }}</strong><p>{{ t('historyMismatchBody') }}</p><p>{{ t('historyMismatchTest') }}</p></div><button class="button compact" @click="tab = 'connection'">{{ t('connection') }}</button></div>
          <div v-if="inspection" class="migration-summary"><div><span>{{ t('currentVersion') }}</span><strong class="mono">{{ inspection.current ?? '—' }}</strong></div><div><span>{{ t('pending') }}</span><strong class="accent">{{ pending.toLocaleString(locale) }}</strong></div><div><span>{{ t('applied') }}</span><strong>{{ applied.toLocaleString(locale) }}</strong></div></div>
          <input v-if="migrations.length" v-model="migrationQuery" class="migration-search" type="search" :aria-label="t('searchMigrations')" :placeholder="t('searchMigrations')"><Pagination v-if="migrations.length" v-model="migrationPage" :total="matchingMigrations.length" :size="migrationPageSize" /><div v-if="migrations.length" class="table-scroll"><table class="migration-table"><thead><tr><th>{{ t('version') }}</th><th>{{ t('script') }}</th><th>{{ t('state') }}</th></tr></thead><tbody><tr v-for="m in visibleMigrations" :key="`${m.script}-${m.state}`"><td><span class="version-tag">{{ m.version ?? 'R' }}</span></td><td><span class="script-name"><Icon name="file" :size="16" /><code>{{ m.script }}</code></span><small>{{ m.description }}</small></td><td><Status :value="m.state ?? 'UNKNOWN'" /></td></tr></tbody></table></div>
          <div v-else class="migration-empty"><div class="empty-icon"><Icon :name="inspection ? 'folder' : 'database'" :size="32" /></div><h3>{{ t(inspection ? 'noMigrations' : 'neverChecked') }}</h3><p>{{ t(inspection ? 'noMigrationsBody' : 'neverCheckedBody') }}</p><button v-if="!inspection" class="button" :disabled="submitting || !!running" @click="action('inspect')">{{ t('refresh') }}<Icon name="arrow" /></button></div>
          <Pagination v-if="migrations.length" v-model="migrationPage" :total="matchingMigrations.length" :size="migrationPageSize" />
          <footer class="migration-footer"><code>{{ config?.effective['flydb.locations'] }}</code><button class="text-button" :disabled="submitting || !!running" @click="action('validate')"><Icon name="check" :size="15" />{{ t('validate') }}</button></footer>
          <details class="advanced-actions"><summary>{{ t('advanced') }}</summary><p>{{ t('advancedHint') }}</p><div class="actions"><button class="button" :disabled="!!running || dirty" @click="advanced = 'baseline'">{{ t('baseline') }}</button><button class="button" :disabled="!!running || dirty" @click="action('undo-plan')">{{ t('undo') }}</button><button class="button" :disabled="!!running || dirty" @click="advanced = 'repair'">{{ t('repair') }}</button><button class="button danger" :disabled="submitting || !!running || dirty || !config || !!config.error" @click="advanced = 'clean'; actionError = ''">{{ t('clean') }}</button></div></details>
        </section>
        <ConnectionEditor v-if="config" v-show="tab === 'connection'" :key="profile.id" :profile="profile" :snapshot="config" :known-keys="data?.knownKeys ?? []" :temporary-password="temporaryPasswords[selected] ?? ''" @dirty="dirty = $event" @saved="config = $event" @password="temporaryPasswords[selected] = $event" />
        <section v-if="tab === 'history'" class="run-history"><div class="section-heading"><h2>{{ t('history') }}</h2></div><Pagination v-if="runs.length" v-model="historyPage" :total="runs.length" :size="historyPageSize" /><button v-for="run in visibleRuns" :key="run.id" class="run-row" @click="activeRun = run.id"><span class="run-icon"><Icon name="history" /></span><span><strong>{{ commandName(run.command) }}</strong><small>{{ date(run.startedAt) }} · {{ run.source ?? '—' }}</small></span><Status :value="run.status" /><Icon name="chevron" /></button><Pagination v-if="runs.length" v-model="historyPage" :total="runs.length" :size="historyPageSize" /><div v-if="!runs.length" class="migration-empty"><Icon name="history" :size="32" /><h3>{{ t('noHistory') }}</h3><p>{{ t('noHistoryBody') }}</p></div></section>
      </div>
    </main>
  </div>
  <NModal v-model:show="allHistory">      <section class="dialog-surface run-dialog run-history">
        <div class="section-heading"><div><span class="eyebrow">FLYDB</span><h1>{{ t('allRuns') }}</h1><p>{{ t('noHistoryBody') }}</p></div><button class="button" @click="allHistory = false">{{ t('close') }}</button></div>
        <Pagination v-if="allRuns.length" v-model="allHistoryPage" :total="allRuns.length" :size="historyPageSize" /><button v-for="run in visibleAllRuns" :key="run.id" class="run-row" @click="activeRun = run.id"><span class="run-icon"><Icon name="history" /></span><span><strong>{{ run.profileName || run.configPath || 'Flydb' }}</strong><small>{{ commandName(run.command) }} · {{ date(run.startedAt) }} · {{ run.source ?? '—' }}</small></span><Status :value="run.status" /><Icon name="chevron" /></button>
        <Pagination v-if="allRuns.length" v-model="allHistoryPage" :total="allRuns.length" :size="historyPageSize" /><p v-if="!allRuns.length" class="migration-empty">{{ t('noHistory') }}</p>
      </section>
</NModal>
  <NModal :show="profileModal" :mask-closable="false" :close-on-esc="false" @esc="profileDialog?.cancel()"><div class="dialog-surface profile-surface" role="dialog" :aria-label="t('addConfig')" aria-modal="true"><ProfileDialog ref="profileDialog" :initial-directory="data?.initialDirectory ?? '/'" :profile="profileMode === 'add' ? undefined : profile" :mode="profileMode" :default-drivers="data?.driversDirectory ?? ''" :known-keys="data?.knownKeys ?? []" @done="profileSaved" @cancel="profileModal = false" /></div></NModal>
  <!-- Keep modal roots mounted through leave; Naive UI releases the document scroll lock in after-leave. -->
  <NModal :show="!!viewedRun" @update:show="value => { if (!value) activeRun = undefined }"><div class="dialog-surface run-dialog"><div v-if="runDetail.error.value" class="notice error"><p>{{ runDetail.error.value }}</p><button class="button" @click="runDetail.retry.value++">{{ t('retry') }}</button></div><RunPanel v-if="viewedRun" :key="viewedRun.id" :run="viewedRun" @close="activeRun = undefined" /></div></NModal>
  <NModal :show="!!viewedPlan" :mask-closable="false" @update:show="value => { if (!value) previewRun = undefined }"><div class="dialog-surface plan-dialog"><div v-if="planDetail.error.value" class="notice error"><p>{{ planDetail.error.value }}</p><button class="button" @click="planDetail.retry.value++">{{ t('retry') }}</button></div><PlanPanel v-if="viewedPlan" :key="viewedPlan.id" :run="viewedPlan" :busy="submitting" @close="previewRun = undefined" @execute="action(viewedPlan.command === 'undo-plan' ? 'undo' : 'migrate', { planId: viewedPlan.result?.planId })" /></div></NModal>
  <NModal :show="discardTarget !== undefined" :mask-closable="false"><div class="dialog-surface"><h2>{{ t('discardTitle') }}</h2><p>{{ t('discardBody') }}</p><footer class="dialog-actions"><button class="button" @click="discardTarget = undefined">{{ t('cancel') }}</button><button class="button primary" @click="selected = discardTarget!; discardTarget = undefined">{{ t('discard') }}</button></footer></div></NModal>
  <NModal :show="!!advanced" :mask-closable="false"><div class="dialog-surface"><CleanDialog v-if="advanced === 'clean' && profile && config" :key="profile.id" :profile="profile" :revision="config.revision" :password="temporaryPasswords[selected] ?? ''" :busy="submitting" :error="actionError" @close="advanced = undefined" @execute="action('clean', $event)" /><template v-else-if="advanced && advanced !== 'clean'"><h2>{{ t(advanced) }}</h2><p>{{ t(advanced === 'remove' ? 'removeBody' : advanced === 'baseline' ? 'baselineBody' : 'repairBody') }}</p><p class="target-line"><code>{{ config?.effective['flydb.url'] }}</code></p><label v-if="advanced === 'baseline'" class="field"><span>{{ t('baselineVersion') }}</span><input v-model="baselineVersion" required /></label><footer class="dialog-actions"><button class="button" @click="advanced = undefined">{{ t('cancel') }}</button><button class="button primary" :disabled="submitting" @click="confirmAdvanced">{{ t('confirmOperation') }}</button></footer></template></div></NModal>
</NConfigProvider>
</template>
