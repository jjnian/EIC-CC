<script setup lang="ts">
import { Button } from '@/components/ui/button';

defineProps<{
  healthData: any;
  metricsData: any;
}>();
defineEmits<{ (e: 'refresh'): void }>();
</script>

<template>
  <section>
    <div class="sv-section-head">
      <div>
        <h3>系统监控</h3>
        <p>实时查看系统健康状态和 LLM 调用统计。</p>
      </div>
      <Button variant="secondary" size="sm" @click="$emit('refresh')">🔄 刷新</Button>
    </div>

    <div v-if="healthData" class="pref-card" style="margin-bottom:16px">
      <div class="pref-row">
        <div style="display:flex;gap:24px;flex-wrap:wrap;width:100%">
          <div>
            <span style="color:rgba(255,255,255,0.5);font-size:12px">状态</span><br/>
            <span :style="{ color: healthData.status === 'UP' ? '#22dd88' : '#ef4444' }">{{ healthData.status }}</span>
          </div>
          <div>
            <span style="color:rgba(255,255,255,0.5);font-size:12px">内存</span><br/>
            <span>{{ healthData.freeMemoryMb }}MB / {{ healthData.totalMemoryMb }}MB</span>
          </div>
          <div>
            <span style="color:rgba(255,255,255,0.5);font-size:12px">数据目录</span><br/>
            <span :style="{ color: healthData.dataDir ? '#22dd88' : '#ef4444' }">{{ healthData.dataDir ? '正常' : '异常' }}</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="metricsData" class="pref-card">
      <div class="pref-row" style="flex-direction:column;align-items:flex-start">
        <h4 style="margin:0 0 12px;font-size:14px;color:var(--text-main)">LLM 调用统计</h4>
        <div style="display:flex;gap:24px;flex-wrap:wrap">
          <div>
            <span style="color:rgba(255,255,255,0.5);font-size:12px">总调用</span><br/>
            <span style="font-size:20px;font-weight:600">{{ metricsData.totalCalls }}</span>
          </div>
          <div>
            <span style="color:rgba(255,255,255,0.5);font-size:12px">总错误</span><br/>
            <span style="font-size:20px;font-weight:600;color:#ef4444">{{ metricsData.totalErrors }}</span>
          </div>
          <div>
            <span style="color:rgba(255,255,255,0.5);font-size:12px">平均延迟</span><br/>
            <span style="font-size:20px;font-weight:600">{{ metricsData.avgLatencyMs }}ms</span>
          </div>
        </div>
        <div v-if="metricsData.byModel && metricsData.byModel.length" style="margin-top:16px;width:100%">
          <h5 style="margin:0 0 8px;color:rgba(255,255,255,0.6);font-size:13px">按模型统计</h5>
          <div v-for="m in metricsData.byModel" :key="m.model" style="display:flex;justify-content:space-between;padding:4px 0;border-bottom:1px solid rgba(255,255,255,0.06)">
            <span>{{ m.model }}</span>
            <span style="color:rgba(255,255,255,0.5)">{{ m.calls }} 次调用 · {{ m.errors }} 错误</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="!healthData && !metricsData" class="empty-state">
      <p>加载中...</p>
    </div>
  </section>
</template>
