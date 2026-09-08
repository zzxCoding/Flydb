<script setup lang="ts">
import { ref, shallowRef, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '../api'
import { explainError } from '../errors'
import type { Profile } from '../types'
import Icon from './Icon.vue'
const props = defineProps<{ profile: Profile; revision: string; password: string; busy: boolean; error?: string }>()
const emit = defineEmits<{ close: []; execute: [input: { cleanToken: string; confirmationText: string; confirmed: boolean }] }>()
const { t, te } = useI18n()
const prepared = shallowRef<{ token: string; target: string; user: string }>()
const acknowledged = ref(false), typed = ref(''), loading = ref(false), failure = ref(''), reload = ref(0)
const ready = computed(() => !!prepared.value && acknowledged.value && typed.value === 'CLEAN' && !loading.value && !props.busy)
watch([() => props.profile.id, () => props.profile.configPath, () => props.profile.workingDirectory,
  () => props.profile.driversDirectory, () => props.revision, () => props.password, reload], async (_, __, cleanup) => {
  let cancelled = false; cleanup(() => { cancelled = true })
  prepared.value = undefined; acknowledged.value = false; typed.value = ''; failure.value = ''; loading.value = true
  try {
    const result = await api<{ token: string; target: string; user: string }>(`/profiles/${props.profile.id}/clean-confirmation`, { revision: props.revision, password: props.password || undefined })
    if (!cancelled) prepared.value = result
  } catch (e) { if (!cancelled) failure.value = explainError(e, t, te) }
  finally { if (!cancelled) loading.value = false }
}, { immediate: true })
function confirm() { if (ready.value && prepared.value) emit('execute', { cleanToken: prepared.value.token, confirmationText: typed.value, confirmed: acknowledged.value }) }
</script>
<template><article class="clean-dialog">
  <header class="dialog-header"><h2>{{ t('clean') }}</h2><button class="icon-button" :aria-label="t('close')" @click="emit('close')"><Icon name="close" /></button></header>
  <div class="notice error" role="alert"><Icon name="alert" /><div><strong>{{ t('cleanWarning') }}</strong><p>{{ t('cleanScope') }}</p><p>{{ t('cleanIrreversible') }}</p></div></div>
  <div class="plan-target"><strong>{{ profile.name }}</strong><span v-if="profile.environment">{{ profile.environment }}</span><code>{{ prepared?.target ?? t('loadingRun') }}</code><span v-if="prepared">{{ t('user') }}: {{ prepared.user }}</span></div>
  <p class="muted small">{{ t('cleanScopeNote') }}</p>
  <p v-if="failure || error" class="notice error">{{ failure || error }}</p>
  <button v-if="failure || error" class="button" :disabled="loading || busy" @click="reload++">{{ t('cleanReload') }}</button>
  <label class="clean-ack"><input v-model="acknowledged" type="checkbox" :disabled="!prepared || busy">{{ t('cleanAcknowledge') }}</label>
  <label class="field"><span>{{ t('cleanType') }}</span><input v-model="typed" autocomplete="off" spellcheck="false" :disabled="!prepared || busy" :placeholder="'CLEAN'"></label>
  <footer class="dialog-actions"><button class="button" @click="emit('close')">{{ t('cancel') }}</button><button class="button danger" :disabled="!ready" @click="confirm">{{ t('confirmClean') }}</button></footer>
</article></template>
