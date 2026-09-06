<script setup lang="ts">
import { ref, computed, watch, defineAsyncComponent } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError } from '../api'
import type { Profile, ConfigSnapshot } from '../types'
import { useConfigDraft, type ConfigDocument } from '../config-draft'
import { explainError } from '../errors'
import ConfigurationFields from './ConfigurationFields.vue'
const ConfigTextEditor = defineAsyncComponent(() => import('./ConfigTextEditor.vue'))
import Icon from './Icon.vue'
const props = defineProps<{ profile: Profile; snapshot: ConfigSnapshot; temporaryPassword: string; knownKeys: string[] }>()
const emit = defineEmits<{ dirty: [value: boolean]; saved: [value: ConfigSnapshot]; password: [value: string] }>()
const { t, te } = useI18n()
const base = ref(props.snapshot), incoming = ref(props.snapshot)
const busy = ref(false), failure = ref<unknown>(), changed = ref(false), notice = ref('')
const comparing = ref(false), choices = ref<Record<string, 'draft' | 'file'>>({})
const externalDocument = ref<ConfigDocument>(), confirmReset = ref(false)
const error = computed(() => failure.value ? explainError(failure.value, t, te) : '')
const convert = (input: { content: string; values?: Record<string, string | null>; validate: boolean }) => api<ConfigDocument>('/config/document', input)
const editor = useConfigDraft(props.snapshot.values, async () => {
  const result = await api<ConfigDocument>(`/profiles/${props.profile.id}/document`)
  if (result.revision !== base.value.revision) { changed.value = true; throw new ApiError('CONFIG_CONFLICT', '', 409) }
  return result
}, convert)
const { fields: draft, content, mode, loaded, dirty, fieldChanges: edits } = editor
const conflicts = computed(() => Object.keys(edits.value).filter(key =>
  incoming.value.values[key] !== base.value.values[key] && incoming.value.values[key] !== draft.value[key]))
watch(dirty, value => emit('dirty', value), { immediate: true, flush: 'sync' })
watch([draft, content], () => { notice.value = '' }, { deep: true, flush: 'sync' })
watch(() => props.snapshot, value => { incoming.value = value; changed.value = value.revision !== base.value.revision })
async function attempt(action: () => Promise<void>) {
  busy.value = true; failure.value = undefined; notice.value = ''
  try { await action() } catch (e) { failure.value = e; if (e instanceof ApiError && e.code === 'CONFIG_CONFLICT') changed.value = true }
  finally { busy.value = false }
}
function switchMode(next: 'form' | 'file') { return attempt(() => editor.switchMode(next)) }
function validate() { return attempt(async () => {
  if (loaded.value) await editor.document(true)
  else await convert({ content: '', values: draft.value, validate: true })
  notice.value = 'configChecked'
}) }
function save() {
  if (busy.value || !dirty.value || comparing.value || externalDocument.value) return
  return attempt(async () => {
    const document = loaded.value ? await editor.document(true) : undefined
    const input = document ? { revision: base.value.revision, content: document.content } : { revision: base.value.revision, values: edits.value }
    const saved = await api<ConfigSnapshot>(`/profiles/${props.profile.id}/config`, input, 'PUT')
    base.value = saved; incoming.value = saved
    if (document) editor.accept(document); else editor.reset(saved.values)
    changed.value = false; notice.value = 'saved'; emit('saved', saved)
  })
}
function reload() { return attempt(async () => {
  incoming.value = await api<ConfigSnapshot>(`/profiles/${props.profile.id}/config`)
  if (loaded.value) {
    if (mode.value === 'form') await editor.switchMode('file')
    externalDocument.value = await api<ConfigDocument>(`/profiles/${props.profile.id}/document`)
  } else {
    choices.value = Object.fromEntries(conflicts.value.map(key => [key, 'file']))
    if (Object.keys(edits.value).length) comparing.value = true
    else merge()
  }
}) }
function merge() {
  const merged = { ...incoming.value.values }
  for (const [key, value] of Object.entries(edits.value)) {
    if (conflicts.value.includes(key) && choices.value[key] !== 'draft') continue
    if (value === null) delete merged[key]; else merged[key] = value
  }
  base.value = incoming.value; editor.reset(incoming.value.values); draft.value = merged
  changed.value = false; comparing.value = false; failure.value = undefined
}
function resolveSource(useFile: boolean) {
  const latest = externalDocument.value!
  if (useFile) editor.accept(latest); else editor.rebase(latest)
  base.value = { ...incoming.value, revision: latest.revision }
  changed.value = false; externalDocument.value = undefined; failure.value = undefined
}
function reset() { return attempt(async () => {
  const latest = await api<ConfigSnapshot>(`/profiles/${props.profile.id}/config`)
  base.value = latest; incoming.value = latest; editor.reset(latest.values)
  changed.value = false; comparing.value = false; externalDocument.value = undefined; confirmReset.value = false
}) }
function source(key: string) { const parts = props.snapshot.sources[key]?.split(':') ?? ['FILE']; return t(`sources.${parts[0]}`) + (parts[1] ? ` · ${parts[1]}` : '') }
</script>
<template>
  <form class="connection-editor" @submit.prevent="save">
    <div class="section-heading editor-heading"><div><h2>{{ t('basicSettings') }}</h2><p>{{ profile.configPath }}</p></div><span class="draft-badge" v-if="dirty">{{ t('unsaved') }}</span></div>
    <div class="editor-controls"><div class="segmented editor-modes" role="group" :aria-label="t('editingMode')"><button type="button" :aria-pressed="mode === 'form'" :disabled="busy" @click="switchMode('form')"><Icon name="settings" />{{ t('formMode') }}</button><button type="button" :aria-pressed="mode === 'file'" :disabled="busy" @click="switchMode('file')"><Icon name="file" />{{ t('fileMode') }}</button></div><span class="muted">{{ t(mode === 'file' ? 'fileModeHint' : 'formModeHint') }}</span></div>
    <div v-if="changed" class="notice warning"><Icon name="alert" /><div><strong>{{ t('fileChanged') }}</strong><p>{{ t('fileChangedBody') }}</p></div><button class="button" type="button" :disabled="busy" @click="reload">{{ t('reload') }}</button></div>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <p v-if="notice" class="notice success" role="status"><Icon name="check" />{{ t(notice) }}</p>
    <section v-if="comparing" class="change-comparison"><h3>{{ t('compareChanges') }}</h3><div v-for="key in Object.keys(edits)" :key="key" class="change-row"><code>{{ key }}</code><div><span>{{ t('fileValue') }}</span><pre>{{ incoming.values[key] ?? '—' }}</pre><span>{{ t('draftValue') }}</span><pre>{{ /password|secret|token|credential/i.test(key) ? t('masked') : draft[key] ?? '—' }}</pre></div><select v-if="conflicts.includes(key)" v-model="choices[key]" :aria-label="key"><option value="file">{{ t('useFile') }}</option><option value="draft">{{ t('keepDraft') }}</option></select></div><button class="button" type="button" @click="merge">{{ t('resolveChanges') }}</button></section>
    <section v-if="externalDocument" class="source-comparison"><h3>{{ t('currentFile') }}</h3><ConfigTextEditor :model-value="externalDocument.content" :known-keys="knownKeys" readonly :label="t('currentFile')" /><p>{{ t('sourceConflictHint') }}</p><div class="actions"><button type="button" class="button" @click="resolveSource(true)">{{ t('loadFileDiscard') }}</button><button type="button" class="button primary" @click="resolveSource(false)">{{ t('keepSourceDraft') }}</button></div><h3>{{ t('myFileDraft') }}</h3></section>
    <fieldset :disabled="busy" class="editor-fields">
      <ConfigurationFields v-if="mode === 'form'" v-model="draft" :known-keys="knownKeys" :secret-keys="snapshot.secretKeys" :masked-url="snapshot.effective['flydb.url']" />
      <ConfigTextEditor v-else v-model="content" :known-keys="knownKeys" :disabled="busy" @save="save" />
    </fieldset>
    <div v-if="confirmReset" class="notice warning"><span>{{ t('resetDraftHint') }}</span><button type="button" class="button" @click="confirmReset = false">{{ t('cancel') }}</button><button type="button" class="button" :disabled="busy" @click="reset">{{ t('discard') }}</button></div>
    <div class="editor-savebar"><span class="muted">{{ t('saveFileOnly') }}</span><div class="actions"><button class="text-button" type="button" :disabled="busy || !dirty" @click="confirmReset = true">{{ t('resetDraft') }}</button><button class="button" type="button" :disabled="busy" @click="validate">{{ t('checkConfig') }}</button><button class="button primary" type="submit" :disabled="busy || !dirty || comparing || !!externalDocument || changed"><Icon name="check" />{{ t(busy ? 'saving' : 'save') }}</button></div></div>
    <details class="settings-section"><summary>{{ t('temporaryPassword') }}<Icon name="chevron" /></summary><div class="session-password"><label class="field"><span>{{ t('temporaryPassword') }}</span><input type="password" autocomplete="new-password" :value="temporaryPassword" @input="emit('password', ($event.target as HTMLInputElement).value)" /><small>{{ t('temporaryHint') }}</small></label></div></details>
    <details class="settings-section"><summary>{{ t('effective') }}<Icon name="chevron" /></summary><p class="muted">{{ t('effectiveHint') }}</p><dl class="effective-values"><dt>{{ t('workingDirectory') }}</dt><dd>{{ profile.workingDirectory }}</dd><dt>{{ t('driversDirectory') }}</dt><dd>{{ profile.driversDirectory || '—' }}</dd></dl><dl class="effective-values"><template v-for="(value, key) in snapshot.effective" :key="key"><dt><code>{{ key }}</code><small>{{ source(String(key)) }}</small></dt><dd>{{ value }}</dd></template></dl></details>
  </form>
</template>
