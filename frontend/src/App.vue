<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ChatView from './components/ChatView.vue'
import SemanticView from './components/SemanticView.vue'
import EvalView from './components/EvalView.vue'
import AuditView from './components/AuditView.vue'
import LoginView from './components/LoginView.vue'
import { api, clearSession, getStoredUser, getToken, storeSession, storeUser } from './api'
import type { AuthUser, LoginResponse } from './api'

type ViewKey = 'chat' | 'semantic' | 'eval' | 'audit'

const allViews: { key: ViewKey; label: string; adminOnly?: boolean }[] = [
  { key: 'chat', label: '问数' },
  { key: 'semantic', label: '语义层' },
  { key: 'eval', label: '评测', adminOnly: true },
  { key: 'audit', label: '审计' },
]

function initialView(): ViewKey {
  const hash = window.location.hash.replace('#', '') as ViewKey
  return allViews.some((item) => item.key === hash) ? hash : 'chat'
}

const user = ref<AuthUser | null>(getStoredUser())
const booting = ref(Boolean(getToken()))
const view = ref<ViewKey>(initialView())
const theme = ref(localStorage.getItem('chatbi.theme')
  ?? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'))

const isAdmin = computed(() => Boolean(user.value?.admin))
const views = computed(() => allViews.filter((item) => !item.adminOnly || isAdmin.value))
const roleLabel = computed(() => {
  const roles = user.value?.roles ?? []
  if (roles.includes('ADMIN')) return '管理员'
  if (roles.includes('MANAGER')) return '受限用户'
  if (roles.includes('VIEWER')) return '只读用户'
  return user.value?.role ?? '已登录'
})

function allowed(next: ViewKey): boolean {
  const item = allViews.find((entry) => entry.key === next)
  if (!item) return false
  return !item.adminOnly || isAdmin.value
}

function navigate(next: ViewKey) {
  if (!allowed(next)) return
  view.value = next
  history.replaceState(null, '', `#${next}`)
}

function onHashChange() {
  const next = initialView()
  view.value = allowed(next) ? next : 'chat'
}

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
}

function onLoggedIn(login: LoginResponse) {
  storeSession(login)
  user.value = login.user
  booting.value = false
  const next = initialView()
  view.value = allowed(next) ? next : 'chat'
}

function logout() {
  clearSession()
  user.value = null
  view.value = 'chat'
  history.replaceState(null, '', '#chat')
}

function onUnauthorized() {
  user.value = null
  view.value = 'chat'
}

watch(theme, (value) => {
  document.documentElement.dataset.theme = value
  localStorage.setItem('chatbi.theme', value)
}, { immediate: true })

watch(isAdmin, (admin) => {
  if (!admin && view.value === 'eval') {
    navigate('chat')
  }
})

onMounted(async () => {
  window.addEventListener('hashchange', onHashChange)
  window.addEventListener('chatbi:unauthorized', onUnauthorized)
  if (getToken()) {
    try {
      const current = await api.me()
      storeUser(current)
      user.value = current
    } catch {
      clearSession()
      user.value = null
    }
  }
  booting.value = false
  if (!allowed(view.value)) {
    view.value = 'chat'
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', onHashChange)
  window.removeEventListener('chatbi:unauthorized', onUnauthorized)
})
</script>

<template>
  <div v-if="booting" class="boot-screen">
    <span class="spinner"></span>
    <span class="muted">正在校验登录状态…</span>
  </div>

  <LoginView
    v-else-if="!user"
    :theme="theme"
    @logged-in="onLoggedIn"
    @toggle-theme="toggleTheme"
  />

  <div v-else class="shell">
    <header class="topbar">
      <button class="brand" type="button" style="border: 0; background: transparent; padding: 0; text-align: left"
        @click="navigate('chat')">
        <span class="brand-mark">BI</span>
        <span>
          <span class="brand-name">ChatBI</span>
          <span class="brand-sub">企业数据智能问答</span>
        </span>
      </button>

      <nav class="nav" aria-label="主导航">
        <button
          v-for="item in views"
          :key="item.key"
          type="button"
          :class="{ active: view === item.key }"
          @click="navigate(item.key)"
        >
          {{ item.label }}
        </button>
      </nav>

      <div class="top-actions">
        <div class="user-chip" :title="user.role">
          <span class="user-avatar">{{ user.displayName?.slice(0, 1) || user.username.slice(0, 1) }}</span>
          <span class="user-meta">
            <span class="user-name">{{ user.displayName || user.username }}</span>
            <span class="user-role">{{ roleLabel }}</span>
          </span>
        </div>
        <button class="icon-btn" type="button" aria-label="退出登录" title="退出登录" @click="logout">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M6.5 2.5H3.5a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1h3M10 5.5 12.5 8 10 10.5M6.5 8h6"
              stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
        <button class="icon-btn" type="button" :aria-label="theme === 'dark' ? '切换浅色模式' : '切换深色模式'"
          @click="toggleTheme">
          <svg v-if="theme === 'light'" width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M13.5 9.6A6 6 0 0 1 6.4 2.5 5.5 5.5 0 1 0 13.5 9.6Z"
              stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" />
          </svg>
          <svg v-else width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <circle cx="8" cy="8" r="3" stroke="currentColor" stroke-width="1.4" />
            <path d="M8 1.4v1.8M8 12.8v1.8M1.4 8h1.8M12.8 8h1.8M3.3 3.3l1.3 1.3M11.4 11.4l1.3 1.3M12.7 3.3l-1.3 1.3M4.6 11.4l-1.3 1.3"
              stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
          </svg>
        </button>
      </div>
    </header>

    <main class="view" :class="{ 'chat-mode': view === 'chat' }">
      <ChatView
        v-show="view === 'chat'"
        :key="user.username"
        :user="user.username"
        :theme="theme"
      />
      <SemanticView v-if="view === 'semantic'" :key="user.username" :is-admin="isAdmin" />
      <EvalView v-if="view === 'eval' && isAdmin" :key="user.username" :is-admin="isAdmin" />
      <AuditView v-if="view === 'audit'" :key="user.username" :is-admin="isAdmin" />
    </main>
  </div>
</template>
