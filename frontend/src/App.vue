<template>
  <div class="shell">
    <AppSidebar />
    <div class="shell-content">
      <RouterView />
    </div>
  </div>
</template>

<script setup>
import AppSidebar from './components/AppSidebar.vue'
</script>

<style>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}
.shell-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
}
</style>
