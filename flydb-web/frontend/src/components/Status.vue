<script setup lang="ts">
import { useI18n } from 'vue-i18n'
defineProps<{ value: string }>()
const { t, te } = useI18n()
</script>
<template><span class="status" :data-state="value"><i aria-hidden="true"></i>{{ te(`states.${value}`) ? t(`states.${value}`) : value }}</span></template>
