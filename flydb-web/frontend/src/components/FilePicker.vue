<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { explainError } from '../errors'
import { api } from '../api'
import type { FileEntry } from '../types'
import Icon from './Icon.vue'
const props = defineProps<{ initial: string; directory?: boolean }>()
const emit = defineEmits<{ select: [path: string]; cancel: [] }>()
const { t, te } = useI18n()
const path = ref(props.initial), parent = ref(''), entries = ref<FileEntry[]>([]), error = ref(''), loading = ref(false)
async function browse(next = path.value) {
  loading.value = true; error.value = ''
  try {
    const data = await api<{ path: string; parent: string; entries: FileEntry[] }>(`/files?path=${encodeURIComponent(next)}`)
    path.value = data.path; parent.value = data.parent; entries.value = data.entries
  } catch (e) { error.value = explainError(e, t, te) } finally { loading.value = false }
}
onMounted(() => browse())
</script>
<template>
  <div class="file-picker">
    <h2>{{ t(directory ? 'selectDirectory' : 'selectFile') }}</h2>
    <form class="path-bar" @submit.prevent="browse()"><button class="icon-button" type="button" :title="t('parent')" :disabled="!parent || loading" @click="browse(parent)"><Icon name="up" /></button><input v-model="path" :aria-label="t('path')" /><button type="submit" class="button">{{ t('open') }}</button></form>
    <p v-if="error" role="alert" class="error-text">{{ error }}</p>
    <div class="file-list" :aria-busy="loading">
      <button v-for="entry in entries.filter(e => e.directory || !directory)" :key="entry.path" type="button" class="file-entry" @click="entry.directory ? browse(entry.path) : emit('select', entry.path)"><Icon :name="entry.directory ? 'folder' : 'file'" /><span>{{ entry.name }}</span><Icon v-if="entry.directory" name="chevron" /></button>
      <p v-if="!entries.length && !loading" class="muted empty-small">{{ t('directoryEmpty') }}</p>
    </div>
    <footer class="dialog-actions"><button class="button" @click="emit('cancel')">{{ t('cancel') }}</button><button v-if="directory" class="button primary" @click="emit('select', path)">{{ t('select') }}</button></footer>
  </div>
</template>
