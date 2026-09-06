<script setup lang="ts">
defineProps<{ name: string; size?: number }>()
const paths: Record<string, string> = {
  plus: 'M12 5v14M5 12h14', search: 'm21 21-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  database: 'M20 6c0 2.2-3.6 4-8 4S4 8.2 4 6s3.6-4 8-4 8 1.8 8 4ZM4 6v12c0 2.2 3.6 4 8 4s8-1.8 8-4V6M4 12c0 2.2 3.6 4 8 4s8-1.8 8-4',
  folder: 'M3 5h7l2 3h9v12H3ZM3 5v15', file: 'M14 2H4v20h16V8ZM14 2v6h6M8 13h8M8 17h5',
  arrow: 'M5 12h14m-6-6 6 6-6 6', chevron: 'm9 5 7 7-7 7', close: 'm6 6 12 12M6 18 18 6',
  refresh: 'M20 7v5h-5M4 17v-5h5M5 7a8 8 0 0 1 14-1l1 6M4 12l1 6a8 8 0 0 0 14-1',
  check: 'm5 12 4 4L19 6', play: 'm7 4 14 8-14 8Z', history: 'M3 3v6h6M4 8a9 9 0 1 1-1 7M12 7v6l4 2',
  settings: 'M4 7h16M4 17h16M8 4v6M16 14v6', more: 'M5 11v2M12 11v2M19 11v2',
  moon: 'M20.9 13A9 9 0 0 1 11 3.1 9 9 0 1 0 20.9 13Z', sun: 'M12 3V1M12 23v-2M3 12H1m22 0h-2M4.2 4.2 2.8 2.8m18.4 18.4-1.4-1.4m0-15.6 1.4-1.4M2.8 21.2l1.4-1.4M17 12a5 5 0 1 1-10 0 5 5 0 0 1 10 0',
  download: 'M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5', copy: 'M9 8h12v13H9ZM16 8V3H3v13h6',
  alert: 'm12 3 10 18H2ZM12 9v5M12 17v1', menu: 'M3 6h18M3 12h18M3 18h18',
  globe: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM3 12h18M12 3c-5 5-5 13 0 18 5-5 5-13 0-18',
  up: 'm6 14 6-6 6 6', trash: 'M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7',
}
</script>
<template><svg :width="size ?? 18" :height="size ?? 18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] ?? paths.file" /></svg></template>
