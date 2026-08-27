import { createRouter, createWebHistory } from 'vue-router';
import { api } from './api.js';

const routes = [
  { path: '/login', name: 'login', component: () => import('./views/LoginView.vue') },
  {
    path: '/',
    component: () => import('./layouts/GlobalShell.vue'),
    children: [
      { path: '', name: 'library', component: () => import('./views/LibraryView.vue') },
      { path: 'workspace/:mediaId', name: 'workspace', component: () => import('./views/WorkspaceView.vue') },
      { path: 'workspace/:mediaId/trace', name: 'trust-trace', component: () => import('./views/TrustTraceView.vue') },
      { path: 'knowledge', name: 'knowledge', component: () => import('./views/KnowledgeView.vue') },
      { path: 'settings', name: 'settings', component: () => import('./views/SettingsView.vue') },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
];

const router = createRouter({ history: createWebHistory(), routes });

router.beforeEach((to) => {
  const loggedIn = !!api.token();
  if (to.name !== 'login' && !loggedIn) return { name: 'login' };
  if (to.name === 'login' && loggedIn) return { name: 'library' };
  return true;
});

export default router;
