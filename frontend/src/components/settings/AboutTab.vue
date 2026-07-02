<script setup lang="ts">
defineProps<{ version: string }>();
</script>

<template>
  <section>
    <div class="sv-section-head">
      <div>
        <h3>关于</h3>
        <p>推演平台 (Forward Simulation Platform)</p>
      </div>
    </div>

    <div class="about-card">
      <div class="about-logo">推</div>
      <div class="about-info">
        <h4>推演平台</h4>
        <div class="about-version">v{{ version }}</div>
        <p>业务血缘图构建工具：本体抽取 + 数据供血。支持 Claude / GPT / DeepSeek / 通义千问 / Kimi / GLM 等主流大模型。</p>
        <div class="about-stack">
          <span class="stack-chip">Vue 3</span>
          <span class="stack-chip">Spring Boot</span>
          <span class="stack-chip">SSE 流式</span>
          <span class="stack-chip">OpenAI / Anthropic 双协议</span>
        </div>
      </div>
    </div>
  </section>
</template>
