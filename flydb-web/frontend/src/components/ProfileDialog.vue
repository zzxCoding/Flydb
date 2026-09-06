<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, defineAsyncComponent } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Profile } from '../types'
import type { ConfigDocument } from '../config-draft'
import { useInitDraft } from '../init-draft'
import ConfigurationFields from './ConfigurationFields.vue'
const ConfigTextEditor = defineAsyncComponent(() => import('./ConfigTextEditor.vue'))
import { explainError } from '../errors'
import { api } from '../api'
import Icon from './Icon.vue'
import FilePicker from './FilePicker.vue'
const props = defineProps<{ initialDirectory: string; profile?: Profile; mode?: 'add' | 'edit' | 'duplicate'; defaultDrivers: string; knownKeys: string[] }>()
const emit = defineEmits<{ done: [profiles: Profile[]]; cancel: []; dirty: [value: boolean] }>()
const { t, te } = useI18n()
const tab = ref<'import' | 'create' | 'discover'>('import'), path = ref(props.profile?.configPath ?? '')
const name = ref(props.profile?.name ?? ''), environment = ref(props.profile?.environment ?? ''), group = ref(props.profile?.group ?? '')
const working = ref(props.profile?.workingDirectory ?? props.initialDirectory)
const drivers = ref(props.profile?.driversDirectory ?? props.defaultDrivers)
const browse = ref<'file' | 'directory' | 'drivers' | null>(null), busy = ref(false), failure = ref<unknown>(), notice = ref(''), discarding = ref(false)
if (props.mode === 'duplicate' && props.profile) {
  path.value = props.profile.configPath.replace(/(\.[^./\\]+)?$/, '-copy$1')
  name.value = `${props.profile.name} — ${t('copySuffix')}`
}
const error = computed(() => failure.value ? explainError(failure.value, t, te) : '')
const convert = (input: { content: string; values?: Record<string, string | null>; validate: boolean }) => api<ConfigDocument>('/config/document', input)
const initialFields = { 'flydb.url': 'jdbc:mysql://localhost:3306/app', 'flydb.user': '' }
const editor = useInitDraft(initialFields, () => working.value,
  directory => api<ConfigDocument>('/config/init', { workingDirectory: directory,
    url: initialFields['flydb.url'], user: initialFields['flydb.user'] }), convert)
const { fields, content, mode: editingMode } = editor
const candidates = ref<string[]>([]), selected = ref<string[]>([])
const baseline = JSON.stringify([path.value, name.value, environment.value, group.value, working.value, drivers.value])
const dirty = computed(() => editor.dirty.value || JSON.stringify([path.value, name.value, environment.value, group.value, working.value, drivers.value]) !== baseline || selected.value.length > 0)
watch(dirty, value => emit('dirty', value), { immediate: true })
watch([fields, content], () => notice.value = '', { deep: true, flush: 'sync' })
function beforeUnload(e: BeforeUnloadEvent) { if (dirty.value) { e.preventDefault(); e.returnValue = '' } }
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => { window.removeEventListener('beforeunload', beforeUnload); emit('dirty', false) })
async function attempt(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; failure.value = undefined; notice.value = ''
  try { await action() } catch (e) { failure.value = e } finally { busy.value = false }
}
function cancel() { if (busy.value) return; if (dirty.value) discarding.value = true; else emit('cancel') }
function switchMode(next: 'form' | 'file') { return attempt(async () => { await editor.document(false); editingMode.value = next }) }
function selectTab(next: 'import' | 'create' | 'discover') {
  if (next !== 'create') { tab.value = next; return }
  return attempt(async () => { await editor.document(false); tab.value = next })
}
function syncDirectory() { if (tab.value === 'create' && editor.loaded.value) return attempt(async () => { await editor.document(false) }) }
function validate() { return attempt(async () => { await editor.document(true); notice.value = 'configChecked' }) }
function discover() { return attempt(async () => {
  const result = await api<{ files: string[] }>(`/discover?path=${encodeURIComponent(working.value)}`)
  candidates.value = result.files; selected.value = [...result.files]
}) }
function save() { if (busy.value) return; return attempt(async () => {
  if (props.mode === 'edit' && props.profile) {
    const profile = await api<Profile>(`/profiles/${props.profile.id}`, { name: name.value, environment: environment.value, group: group.value, workingDirectory: working.value, driversDirectory: drivers.value }, 'PUT')
    emit('done', [profile])
  } else {
    const document = tab.value === 'create' && props.mode !== 'duplicate' ? await editor.document(true) : undefined
    const result = await api<Profile[]>('/profiles', {
      mode: props.mode === 'duplicate' ? 'duplicate' : tab.value, sourceId: props.profile?.id,
      name: name.value, environment: environment.value, group: group.value,
      configPath: path.value, workingDirectory: working.value, paths: selected.value,
      ...(document ? { content: document.content } : {}), driversDirectory: drivers.value,
    })
    emit('done', result)
  }
}) }
defineExpose({ cancel })
</script>
<template>
  <FilePicker v-if="browse" :initial="browse === 'drivers' ? drivers : working || initialDirectory" :directory="browse !== 'file'" @cancel="browse = null" @select="value => { if (browse === 'file') path = value; else if (browse === 'drivers') drivers = value; else working = value; browse = null }" />
  <form v-else class="profile-dialog" @submit.prevent="save">
    <header class="dialog-header"><div><span class="eyebrow">FLYDB</span><h2>{{ t(mode === 'edit' ? 'rename' : mode === 'duplicate' ? 'duplicate' : 'addConfig') }}</h2></div><button type="button" class="icon-button" :disabled="busy" :aria-label="t('close')" @click="cancel"><Icon name="close" /></button></header>
    <div class="profile-dialog-body">
      <div v-if="!mode || mode === 'add'" class="segmented profile-tabs" role="group"><button v-for="item in (['import', 'create', 'discover'] as const)" :key="item" type="button" :disabled="busy" :aria-pressed="tab === item" @click="selectTab(item)">{{ t(item) }}</button></div>
      <p class="muted">{{ t(mode === 'edit' ? 'profileEditHint' : mode === 'duplicate' ? 'duplicateBody' : tab === 'create' ? 'createBody' : 'importBody') }}</p>
      <div v-if="error" class="notice error" role="alert">{{ error }}</div><p v-if="notice" class="notice success" role="status"><Icon name="check" />{{ t(notice) }}</p>
      <fieldset :disabled="busy" class="editor-fields">
        <details class="configuration-target" :open="editingMode === 'form' || tab !== 'create'"><summary><span>{{ t('profileDetails') }}</span><code>{{ name || working }}</code><Icon name="chevron" /></summary>
        <label v-if="tab === 'import' && mode !== 'edit'" class="field"><span>{{ t('configFile') }}</span><div class="path-bar"><input v-model="path" required placeholder="/path/to/flydb.conf" /><button v-if="mode !== 'duplicate'" type="button" class="button" @click="browse = 'file'">{{ t('browse') }}</button></div></label>
        <label class="field"><span>{{ t(tab === 'create' ? 'projectDirectory' : 'workingDirectory') }}</span><div class="path-bar"><input v-model="working" required @change="syncDirectory" /><button type="button" class="button" @click="browse = 'directory'">{{ t('browse') }}</button></div><small v-if="tab === 'create'">{{ t('createLocationHint') }}</small></label>
        <template v-if="tab === 'discover'"><button class="button" type="button" :disabled="busy" @click="discover">{{ t('discover') }}</button><p class="muted">{{ t('scanLimit') }}</p><div class="discovery-list"><label v-for="candidate in candidates" :key="candidate"><input v-model="selected" type="checkbox" :value="candidate" /><code>{{ candidate }}</code></label></div></template>
        <template v-else><div class="form-grid metadata-fields"><label class="field wide"><span>{{ t('name') }} <small>{{ t('optional') }}</small></span><input v-model="name" maxlength="120" :placeholder="t('namePlaceholder')" /></label><label class="field"><span>{{ t('environment') }}</span><input v-model="environment" maxlength="60" placeholder="dev / staging / production" /></label><label class="field"><span>{{ t('group') }}</span><input v-model="group" maxlength="80" /></label></div></template>
        </details>
        <template v-if="tab === 'create'">
          <div class="init-files"><strong>{{ t('initFiles') }}</strong><code>flydb.conf · db/migration/V1__init.sql · drivers/README.md</code><p>{{ t('initSampleHint') }}</p></div>
          <div class="editor-controls"><div class="segmented editor-modes" role="group" :aria-label="t('editingMode')"><button type="button" :disabled="busy" :aria-pressed="editingMode === 'form'" @click="switchMode('form')"><Icon name="settings" />{{ t('formMode') }}</button><button type="button" :disabled="busy" :aria-pressed="editingMode === 'file'" @click="switchMode('file')"><Icon name="file" />{{ t('fileMode') }}</button></div></div>
          <ConfigurationFields v-if="editingMode === 'form'" v-model="fields" :known-keys="knownKeys" />
          <ConfigTextEditor v-else v-model="content" :known-keys="knownKeys" :disabled="busy" @save="save" />
        </template>
        <details class="settings-section"><summary>{{ t('driversDirectory') }}</summary><label class="field"><span>{{ t('driversDirectory') }}</span><div class="path-bar"><input v-model="drivers" /><button type="button" class="button" @click="browse = 'drivers'">{{ t('browse') }}</button></div><small>{{ t('driverHint') }}</small></label></details>
      </fieldset>
      <div v-if="discarding" class="notice warning"><span>{{ t('discardDialogHint') }}</span><button class="button" type="button" @click="discarding = false">{{ t('continueEditing') }}</button><button class="button" type="button" @click="emit('cancel')">{{ t('discard') }}</button></div>
    </div>
    <footer class="dialog-actions"><span v-if="tab === 'create'" class="muted">{{ t('createFileOnly') }}</span><button type="button" class="button" :disabled="busy" @click="cancel">{{ t('cancel') }}</button><button v-if="tab === 'create'" class="button" type="button" :disabled="busy" @click="validate">{{ t('checkConfig') }}</button><button class="button primary" type="submit" :disabled="busy || (tab === 'discover' && !selected.length)">{{ t(busy ? 'saving' : mode === 'edit' ? 'save' : mode === 'duplicate' ? 'duplicate' : tab === 'create' ? 'create' : 'import') }}<Icon name="arrow" /></button></footer>
  </form>
</template>
