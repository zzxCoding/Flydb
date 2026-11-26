<script setup lang="ts">
import { ref } from 'vue'
import { NModal } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import { api } from '../api'
import { explainError } from '../errors'
import Icon from './Icon.vue'

const props = defineProps<{
  profileId: string; revision?: string; releaseDirectory: string; configured?: string
  missingScript?: string; disabled: boolean
}>()
const { t, te } = useI18n()
const draft = ref<{ revision: string; releaseDirectory: string; roots: string[]; missing?: string }>()
const description = ref(''), version = ref(''), directory = ref('')
const busy = ref(false), error = ref('')
const created = ref<{ path: string; version: string }>()

function open() {
  if (props.disabled || !props.revision) return
  description.value = ''; version.value = ''; directory.value = ''; error.value = ''; created.value = undefined
  const roots = props.releaseDirectory ? [props.releaseDirectory] : (props.configured || '').split(',')
  draft.value = { revision: props.revision, releaseDirectory: props.releaseDirectory, missing: props.missingScript,
    roots: roots.map(path => path.trim().replace(/^filesystem:/, '')).filter(Boolean) }
}

async function create() {
  if (busy.value || created.value || !draft.value || !description.value.trim() || props.disabled) return
  busy.value = true; error.value = ''
  try {
    created.value = await api<{ path: string; version: string }>(`/profiles/${props.profileId}/scripts`, {
      revision: draft.value.revision, description: description.value.trim(),
      ...(draft.value.releaseDirectory && { releaseDirectory: draft.value.releaseDirectory }),
      ...(version.value.trim() && { scriptVersion: version.value.trim() }),
      ...(directory.value.trim() && { directory: directory.value.trim() }),
    })
  } catch (e) { error.value = explainError(e, t, te) }
  finally { busy.value = false }
}
</script>

<template>
  <button class="button" :disabled="disabled || !revision" @click="open"><Icon name="plus" />{{ t('newScript') }}</button>
  <NModal :show="!!draft" :mask-closable="!busy" :close-on-esc="!busy" @update:show="show => { if (!show && !busy) draft = undefined }">
    <!-- 焦点组件以同级的 div 识别弹窗内容，关闭动画期间也需要保留这个根节点。 -->
    <div class="dialog-surface new-script" role="dialog" aria-modal="true" :aria-label="t('newScript')">
      <template v-if="created">
        <h2>{{ t('scriptCreated') }}</h2>
        <code class="script-destination">{{ created.path }}</code>
        <p>{{ t('editNewScript') }}</p>
        <footer class="dialog-actions"><button class="button primary" @click="draft = undefined">{{ t('close') }}</button></footer>
      </template>
      <form v-else-if="draft" @submit.prevent="create">
        <h2>{{ t('newScript') }}</h2>
        <p>{{ t('newScriptHint') }}</p>
        <div class="field"><span>{{ t('scriptLocations') }}</span>
          <code v-for="path in draft.roots" :key="path" class="script-destination">{{ path }}</code>
          <small v-if="!draft.roots.length">{{ t('useConfiguredLocations') }}</small>
        </div>
        <p v-if="draft.missing" class="notice warning">{{ t('newScriptMissing', { script: draft.missing }) }}</p>
        <label class="field"><span>{{ t('scriptDescription') }}</span>
          <input v-model="description" name="description" placeholder="add_order_note" autocomplete="off" required :disabled="busy" />
        </label>
        <details class="script-options">
          <summary>{{ t('scriptOptions') }}</summary>
          <label class="field"><span>{{ t('scriptVersion') }}</span><input v-model="version" name="scriptVersion" placeholder="2.1" :disabled="busy" /></label>
          <label class="field"><span>{{ t('scriptSubdirectory') }}</span><input v-model="directory" name="directory" :disabled="busy" /><small>{{ t('scriptSubdirectoryHint') }}</small></label>
        </details>
        <p v-if="error" class="notice error" role="alert">{{ error }}</p>
        <footer class="dialog-actions">
          <button type="button" class="button" :disabled="busy" @click="draft = undefined">{{ t('cancel') }}</button>
          <button type="submit" class="button primary" :disabled="busy || disabled || !description.trim()">{{ t('createScriptFile') }}</button>
        </footer>
      </form>
    </div>
  </NModal>
</template>
