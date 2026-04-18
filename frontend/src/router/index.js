import { createRouter, createWebHistory } from 'vue-router'
import GraphList from '../views/GraphList.vue'
import GraphEditor from '../views/GraphEditor.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/graphs' },
    { path: '/graphs', component: GraphList },
    { path: '/editor/:id', component: GraphEditor },
    // Stub routes — show placeholder until implemented
    { path: '/:catchAll(.*)', redirect: '/graphs' },
  ],
})

export default router
