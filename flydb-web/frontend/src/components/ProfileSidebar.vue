<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NModal } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import { api, ApiError } from '../api'
import type { Profile, Run } from '../types'
import Icon from './Icon.vue'

const props = defineProps<{ profiles: Profile[]; groups: string[]; runs: Run[]; selected: string; stateDirectory: string }>()
const emit = defineEmits<{ choose: [id: string]; changed: [] }>()
const { t } = useI18n()
const query = ref(''), collapsed = ref<string[]>([]), busy = ref(false), error = ref('')
const menu = ref<string>(), editing = ref<{ action: 'create' | 'rename' | 'delete'; name: string }>(), draft = ref('')
const moving = ref<Profile>(), destination = ref('')
const drag = ref<{ kind: 'group' | 'profile'; id: string }>(), over = ref<string>()
const storageKey = computed(() => `flydb.collapsedGroups:${props.stateDirectory}`)
watch(storageKey, key => {
  try { const value: unknown = JSON.parse(localStorage.getItem(key) ?? '[]'); collapsed.value = Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [] }
  catch { collapsed.value = [] }
}, { immediate: true })
function remember() { try { localStorage.setItem(storageKey.value, JSON.stringify(collapsed.value)) } catch { /* Browser storage may be unavailable. */ } }
function toggle(name: string) { collapsed.value = collapsed.value.includes(name) ? collapsed.value.filter(n => n !== name) : [...collapsed.value, name]; remember() }
const names = computed(() => [...new Set([...props.groups, ...props.profiles.map(p => p.group).filter(Boolean)])])
const sections = computed(() => {
  const q = query.value.trim().toLowerCase()
  return [...names.value, ''].map(name => ({ name, profiles: props.profiles.filter(p => (p.group || '') === name && (!q || [p.name, p.environment, p.configPath, p.group].join(' ').toLowerCase().includes(q))) }))
    .filter(section => !q || section.profiles.length || section.name.toLowerCase().includes(q))
})
function edit(action: 'create' | 'rename' | 'delete', name = '') { menu.value = undefined; error.value = ''; draft.value = action === 'rename' ? name : ''; editing.value = { action, name } }
async function organize(body: Record<string, string>) {
  if (busy.value) return false
  busy.value = true; error.value = ''
  try { await api('/groups', body); emit('changed'); return true }
  catch (e) { error.value = e instanceof ApiError && e.code === 'GROUP_EXISTS' ? t('groupExists') : e instanceof ApiError && e.code === 'GROUP_NOT_FOUND' ? t('groupMissing') : String(e); return false }
  finally { busy.value = false; drag.value = undefined; over.value = undefined }
}
async function saveGroup() {
  const edit = editing.value
  if (!edit || (edit.action !== 'delete' && !draft.value.trim())) return
  if (edit.action === 'rename' && draft.value.trim() === edit.name) { editing.value = undefined; return }
  if (await organize({ action: edit.action, name: edit.name, newName: draft.value.trim() })) {
    if (edit.action !== 'create') { collapsed.value = collapsed.value.filter(n => n !== edit.name); remember() }
    editing.value = undefined
  }
}
function startDrag(event: DragEvent, kind: 'group' | 'profile', id: string) {
  if (busy.value || !event.dataTransfer) { event.preventDefault(); return }
  drag.value = { kind, id }; event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', id); menu.value = undefined
}
function dragOver(event: DragEvent, name: string) {
  if (!drag.value || busy.value) return
  event.preventDefault(); if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'; over.value = name
}
async function drop(name: string) {
  const item = drag.value
  if (!item || busy.value) return
  if (await organize(item.kind === 'group' ? { action: 'move', name: item.id, before: name } : { action: 'moveProfile', profileId: item.id, name })) {
    if (item.kind === 'profile' && collapsed.value.includes(name)) toggle(name)
  }
}
function shift(name: string, delta: number) {
  const index = names.value.indexOf(name), target = index + delta
  if (target < 0 || target >= names.value.length) return
  menu.value = undefined
  organize({ action: 'move', name, before: delta < 0 ? names.value[target]! : names.value[target + 1] ?? '' })
}
async function moveProfile() { if (moving.value && await organize({ action: 'moveProfile', profileId: moving.value.id, name: destination.value })) moving.value = undefined }
</script>

<template>
  <label class="search"><Icon name="search" /><input v-model="query" :aria-label="t('search')" :placeholder="t('search')" /></label>
  <div class="group-tools"><button class="text-button" :disabled="busy" @click="edit('create')"><Icon name="plus" :size="14" />{{ t('addGroup') }}</button><small>{{ t('groupDragHint') }}</small></div>
  <p v-if="error && !editing && !moving" class="group-error" role="alert">{{ error }}</p>
  <nav class="profile-list" :aria-label="t('configurations')" @dragend="drag = undefined; over = undefined">
    <section v-for="section in sections" :key="section.name" :data-group="section.name" :class="{ 'group-drop-target': over === section.name }" @dragover="dragOver($event, section.name)" @dragleave="over = undefined" @drop.prevent.stop="drop(section.name)">
      <div class="group-heading">
        <button class="group-toggle" :draggable="!!section.name && !busy" :aria-expanded="!!query.trim() || !collapsed.includes(section.name)" @dragstart="startDrag($event, 'group', section.name)" @click="toggle(section.name)"><Icon name="chevron" :size="12" /><span>{{ section.name || t('ungrouped') }}</span><small>{{ section.profiles.length }}</small></button>
        <button v-if="section.name" class="icon-button group-menu-button" :aria-label="t('groupActions', { name: section.name })" :aria-expanded="menu === section.name" :disabled="busy" @click="menu = menu === section.name ? undefined : section.name"><Icon name="more" :size="16" /></button>
      </div>
      <div v-if="menu === section.name" class="group-menu" @keydown.esc="menu = undefined">
        <button @click="edit('rename', section.name)">{{ t('renameGroup') }}</button><button :disabled="names.indexOf(section.name) === 0" @click="shift(section.name, -1)">{{ t('moveUp') }}</button><button :disabled="names.indexOf(section.name) === names.length - 1" @click="shift(section.name, 1)">{{ t('moveDown') }}</button><button @click="edit('delete', section.name)">{{ t('deleteGroup') }}</button>
      </div>
      <div v-show="!!query.trim() || !collapsed.includes(section.name)">
        <div v-for="p in section.profiles" :key="p.id" class="group-profile" :draggable="!busy" @dragstart.stop="startDrag($event, 'profile', p.id)">
          <button class="profile-row" :aria-current="selected === p.id ? 'page' : undefined" @click="emit('choose', p.id)"><span class="db-glyph"><Icon name="database" :size="17" /></span><span class="profile-title">{{ p.name }}<small>{{ p.environment || p.configPath.split(/[\\/]/).slice(-2).join('/') }}</small></span><span v-if="runs.some(r => r.status === 'RUNNING' && (r.profileId === p.id || r.configPath === p.configPath))" class="activity-dot" /></button>
          <button class="icon-button profile-move" :aria-label="t('moveProfileGroup', { name: p.name })" :disabled="busy" @click="moving = p; destination = p.group || ''; error = ''"><Icon name="folder" :size="14" /></button>
        </div>
        <p v-if="!section.profiles.length" class="group-empty">{{ t('emptyGroup') }}</p>
      </div>
    </section>
    <div v-if="query && !sections.length" class="empty-small"><p>{{ t('noMatch') }}</p><button class="text-button" @click="query = ''">{{ t('clearSearch') }}</button></div>
  </nav>
  <!-- The modal focus trap requires a div content root; form roots redirect input focus to a hidden sentinel. -->
  <NModal :show="!!editing" :mask-closable="!busy" @update:show="value => { if (!value && !busy) editing = undefined }"><div class="dialog-surface"><form @submit.prevent="saveGroup">
    <h2>{{ t(editing?.action === 'delete' ? 'deleteGroup' : editing?.action === 'rename' ? 'renameGroup' : 'addGroup') }}</h2>
    <p v-if="editing?.action === 'delete'">{{ t('deleteGroupBody', { name: editing.name }) }}</p>
    <label v-else class="field"><span>{{ t('group') }}</span><input v-model="draft" maxlength="80" required autofocus :disabled="busy" /></label>
    <p v-if="error" class="group-error" role="alert">{{ error }}</p>
    <footer class="dialog-actions"><button type="button" class="button" :disabled="busy" @click="editing = undefined">{{ t('cancel') }}</button><button class="button primary" :disabled="busy || (editing?.action !== 'delete' && !draft.trim())">{{ t(editing?.action === 'delete' ? 'deleteGroup' : 'saveGroup') }}</button></footer>
  </form></div></NModal>
  <NModal :show="!!moving" :mask-closable="!busy" @update:show="value => { if (!value && !busy) moving = undefined }"><div class="dialog-surface"><form @submit.prevent="moveProfile"><h2>{{ t('moveProfileGroup', { name: moving?.name }) }}</h2><label class="field"><span>{{ t('group') }}</span><select v-model="destination" :disabled="busy"><option value="">{{ t('ungrouped') }}</option><option v-for="name in names" :key="name" :value="name">{{ name }}</option></select></label><p v-if="error" class="group-error" role="alert">{{ error }}</p><footer class="dialog-actions"><button type="button" class="button" :disabled="busy" @click="moving = undefined">{{ t('cancel') }}</button><button class="button primary" :disabled="busy">{{ t('saveGroup') }}</button></footer></form></div></NModal>
</template>
