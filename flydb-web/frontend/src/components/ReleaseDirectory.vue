<script setup lang="ts">
import { computed, ref } from 'vue'
import { NModal } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import FilePicker from './FilePicker.vue'
import Icon from './Icon.vue'

const props = defineProps<{ selected: string; configured?: string; workingDirectory: string; disabled: boolean }>()
const emit = defineEmits<{ choose: [path: string] }>()
const { t } = useI18n()
const open = ref(false)
const defaults = computed(() => (props.configured ?? '').split(',').map(path => path.trim()).filter(Boolean))
const paths = computed(() => props.selected ? [props.selected] : defaults.value)
function shortName(location: string) {
  const path = location.replace(/^filesystem:/, '')
  if (path.startsWith('classpath:')) return path
  return path.split(/[\\/]/).filter(Boolean).pop() || path
}
const defaultNames = computed(() => defaults.value.map(shortName).join(', '))
const initial = computed(() => {
  const active = props.selected || (defaults.value.length === 1 && defaults.value[0]?.startsWith('filesystem:')
    ? defaults.value[0].slice('filesystem:'.length) : '')
  // 直接列出同级目录，方便从上一份发布包切换到下一份。
  const parent = active.replace(/[\\/]+$/, '').match(/^(.*[\\/])[^\\/]+$/)
  return parent?.[1] || props.workingDirectory
})
function choose(path: string) {
  open.value = false
  if (!props.disabled) emit('choose', path)
}
</script>

<template>
  <section class="release-source" :aria-label="t('releaseDirectory')">
    <div class="release-source-row">
      <div class="release-source-info">
        <span class="muted small">{{ t('releaseDirectory') }} · {{ t(selected ? 'thisRelease' : 'environmentDefault') }}</span>
        <details v-if="paths.length" class="release-paths">
          <summary><Icon name="folder" :size="16" /><strong>{{ paths.map(shortName).join(', ') }}</strong><Icon name="chevron" :size="14" /></summary>
          <code v-for="path in paths" :key="path">{{ path }}</code>
        </details>
      </div>
      <div class="actions">
        <button class="button" :disabled="disabled" @click="open = true">{{ t('changeSqlDirectory') }}</button>
        <button v-if="selected" class="text-button" :disabled="disabled" @click="choose('')">
          {{ t('useDefaultLocation', { name: defaultNames }) }}
        </button>
      </div>
    </div>
    <p class="muted small">{{ t('releaseSwitchHint') }}</p>
  </section>
  <NModal v-model:show="open">
    <div class="dialog-surface" role="dialog" :aria-label="t('changeSqlDirectory')" aria-modal="true">
      <FilePicker v-if="open" directory :initial="initial" :heading="t('changeSqlDirectory')" :confirm-label="t('useDirectoryAndCheck')" @cancel="open = false" @select="choose" />
    </div>
  </NModal>
</template>
