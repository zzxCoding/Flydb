<script setup lang="ts">
import { ref, watch } from 'vue'
import { NModal } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import type { Profile, ConfigSnapshot, Run } from '../types'
import { agentContext } from '../agent-context'
import Icon from './Icon.vue'
const props = defineProps<{ profile: Profile; config?: ConfigSnapshot; runs: Run[]; version: string; stateDirectory: string; dirty: boolean; offline: boolean }>()
const { t, locale } = useI18n()
const copied = ref(false), copying = ref(false), fallback = ref(false), text = ref('')
watch(() => props.profile.id, () => { copied.value = false; fallback.value = false })
async function copy() {
  if (copying.value) return
  copied.value = false; copying.value = true
  const id = props.profile.id
  const content = agentContext({ ...props, locale: locale.value })
  try { await navigator.clipboard.writeText(content); if (props.profile.id === id) copied.value = true }
  catch { if (props.profile.id === id) { text.value = content; fallback.value = true } }
  finally { copying.value = false }
}
</script>
<template>
  <div class="agent-handoff"><button class="button agent-copy" :disabled="copying" :title="t('agentCopyHint')" @click="copy"><Icon :name="copied ? 'check' : 'copy'" :size="16" />{{ t('copyForAgent') }}</button><span v-if="copied" class="agent-copy-feedback" role="status">{{ t('agentCopied') }}</span></div>
  <NModal v-model:show="fallback"><div class="dialog-surface"><h2>{{ t('copyForAgent') }}</h2><p>{{ t('agentCopyFallback') }}</p><textarea class="agent-copy-text" :aria-label="t('agentContextLabel')" :value="text" readonly @focus="($event.target as HTMLTextAreaElement).select()" /><footer class="dialog-actions"><button class="button" @click="fallback = false">{{ t('close') }}</button></footer></div></NModal>
</template>
