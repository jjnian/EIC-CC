<script setup lang="ts">
import { ref, watch, onMounted } from 'vue';
import { readFileContent, fileDownloadUrl } from '../../api/dataSources';

const props = defineProps<{ dsId: string; totalChars: number }>();
const offset = ref(0);
const PAGE = 10000;
const text = ref('');
const loading = ref(false);

const load = async () => {
  loading.value = true;
  try { text.value = await readFileContent(props.dsId, offset.value, PAGE); }
  finally { loading.value = false; }
};
const next = () => { if (offset.value + PAGE < props.totalChars) { offset.value += PAGE; load(); } };
const prev = () => { if (offset.value >= PAGE) { offset.value -= PAGE; load(); } };

onMounted(load);
watch(() => props.dsId, () => { offset.value = 0; load(); });
</script>

<template>
  <div class="tab">
    <div class="bar">
      <span>{{ offset }} – {{ Math.min(offset + PAGE, totalChars) }} / {{ totalChars }} 字符</span>
      <button :disabled="offset === 0" @click="prev">‹ 上一页</button>
      <button :disabled="offset + PAGE >= totalChars" @click="next">下一页 ›</button>
      <a :href="fileDownloadUrl(dsId)" target="_blank">下载原文件</a>
    </div>
    <pre v-if="!loading" class="content">{{ text }}</pre>
    <div v-else class="msg">加载中…</div>
  </div>
</template>

<style scoped>
.tab { padding: 12px; display: flex; flex-direction: column; gap: 8px; height: 100%; min-height: 320px; }
.bar { display: flex; align-items: center; gap: 12px; color: #aaa; font-size: 12px; }
.bar button, .bar a { background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 4px 10px; color: #c0c4cf; cursor: pointer; text-decoration: none; font-size: 12px; }
.bar button:disabled { opacity: .4; cursor: not-allowed; }
.content { flex: 1; overflow: auto; background: rgba(255,255,255,.03); padding: 12px; border-radius: 6px; color: #e8eaed; font-family: monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all; }
.msg { color: #888; padding: 12px; }
</style>
