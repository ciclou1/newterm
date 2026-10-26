<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ChatView from './components/ChatView.vue'
import SemanticView from './components/SemanticView.vue'
import EvalView from './components/EvalView.vue'
import AuditView from './components/AuditView.vue'
import LoginView from './components/LoginView.vue'
import ChangePasswordDialog from './components/ChangePasswordDialog.vue'
import ErrorBoundary from './components/ErrorBoundary.vue'
import {
  ApiError, api, clearSession, describeError, getStoredUser, getToken, isTokenExpired,
  storeSession, storeUser,
} from './api'
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
const bootError = ref('')
const loginNotice = ref('')
const view = ref<ViewKey>(initialView())
const showPasswordDialog = ref(false)
const theme = ref(localStorage.getItem('chatbi.theme')
  ?? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'))
let expiryTimer: number | undefined

const isAdmin = computed(() => Boolean(user.value?.admin))
const views = computed(() => allViews.filter((item) => !item.adminOnly || isAdmin.value))
const roleLabel = computed(() => {
  const roles = user.value?.roles ?? []
  if (roles.includes('ADMIN')) return '管理员'
  if (roles.includes('MANAGER')) return '受限用户'
  if (roles.includes('VIEWER')) return '只读用户'
  return user.value?.role ?? '已登录'
})
const displayName = computed(() =>
  user.value?.displayName || user.value?.username || '')

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
  loginNotice.value = ''
  bootError.value = ''
  booting.value = false
  const next = initialView()
  view.value = allowed(next) ? next : 'chat'
  startExpiryWatch()
}

function logout(message = '') {
  clearSession()
  user.value = null
  view.value = 'chat'
  showPasswordDialog.value = false
  loginNotice.value = message
  history.replaceState(null, '', '#chat')
  stopExpiryWatch()
}

function onUnauthorized(event: Event) {
  const reason = event instanceof CustomEvent
    ? (event.detail as { reason?: string } | undefined)?.reason
    : undefined
  logout(reason || '登录已失效，请重新登录。')
}

function onPasswordChanged() {
  showPasswordDialog.value = false
  logout('口令已更新，请使用新口令重新登录。')
}

function startExpiryWatch() {
  stopExpiryWatch()
  expiryTimer = window.setInterval(() => {
    if (user.value && isTokenExpired(getToken(), 0)) {
      logout('登录已过期，请重新登录。')
    }
  }, 30_000)
}

function stopExpiryWatch() {
  window.clearInterval(expiryTimer)
  expiryTimer = undefined
}

async function verifySession() {
  bootError.value = ''
  const token = getToken()
  if (!token) {
    user.value = null
    booting.value = false
    return
  }
  if (isTokenExpired(token)) {
    logout('登录已过期，请重新登录。')
    booting.value = false
    return
  }
  booting.value = true
  try {
    const current = await api.me()
    storeUser(current)
    user.value = current
    startExpiryWatch()
  } catch (caught) {
    if (caught instanceof ApiError && caught.kind === 'auth') {
      // The request layer already cleared the session and raised the event.
      user.value = null
      if (!loginNotice.value) loginNotice.value = '登录已失效，请重新登录。'
    } else {
      // Keep the token: a transient network failure must not destroy a valid
      // session. Offer an explicit retry instead of pretending to be logged out.
      bootError.value = describeError(caught)
      user.value = null
    }
  } finally {
    booting.value = false
  }
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

onMounted(() => {
  window.addEventListener('hashchange', onHashChange)
  window.addEventListener('chatbi:unauthorized', onUnauthorized)
  void verifySession()
})

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', onHashChange)
  window.removeEventListener('chatbi:unauthorized', onUnauthorized)
  stopExpiryWatch()
})
</script>

<template>
  <div v-if="booting" class="boot-screen" role="status">
    <span class="spinner"></span>
    <span class="muted">正在校验登录状态…</span>
  </div>

  <section v-else-if="bootError" class="boot-screen" aria-live="assertive">
    <div class="login-card" style="text-align: center">
      <h1 style="font-size: 20px; margin-top: 0">暂时无法校验登录状态</h1>
      <div class="error-box" role="alert" style="margin-bottom: 14px">{{ bootError }}</div>
      <div style="display: flex; gap: 8px; justify-content: center">
        <button class="btn primary" type="button" @click="verifySession">重试</button>
        <button class="btn" type="button" @click="logout()">返回登录</button>
      </div>
    </div>
  </section>

  <LoginView
    v-else-if="!user"
    :theme="theme"
    :notice="loginNotice"
    @logged-in="onLoggedIn"
    @toggle-theme="toggleTheme"
  />

  <div v-else class="shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <header class="topbar">
      <button
        class="brand"
        type="button"
        style="border: 0; background: transparent; padding: 0; text-align: left"
        aria-label="返回问数首页"
        @click="navigate('chat')"
      >
        <span class="brand-mark" aria-hidden="true">BI</span>
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
          :aria-current="view === item.key ? 'page' : undefined"
          @click="navigate(item.key)"
        >
          {{ item.label }}
        </button>
      </nav>

      <div class="top-actions">
        <div class="user-chip" :title="`${user.username} · ${roleLabel}`">
          <span class="user-avatar" aria-hidden="true">{{ displayName.slice(0, 1) || user.username.slice(0, 1) }}</span>
          <span class="user-meta">
            <span class="user-name">{{ displayName }}</span>
            <span class="user-role">{{ roleLabel }}</span>
          </span>
        </div>
        <button class="icon-btn" type="button" aria-label="修改登录口令" title="修改登录口令"
          @click="showPasswordDialog = true">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <circle cx="5.5" cy="10.5" r="2.5" stroke="currentColor" stroke-width="1.4" />
            <path d="M7.3 8.7 13 3M11 5l1.5 1.5M9.5 6.5 11 8" stroke="currentColor"
              stroke-width="1.4" stroke-linecap="round" />
          </svg>
        </button>
        <button class="icon-btn" type="button" aria-label="退出登录" title="退出登录" @click="logout()">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M6.5 2.5H3.5a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1h3M10 5.5 12.5 8 10 10.5M6.5 8h6"
              stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
        <button
          class="icon-btn"
          type="button"
          :aria-label="theme === 'dark' ? '切换浅色模式' : '切换深色模式'"
          @click="toggleTheme"
        >
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

    <main id="main-content" class="view" :class="{ 'chat-mode': view === 'chat' }" tabindex="-1">
      <ErrorBoundary>
        <ChatView
          v-show="view === 'chat'"
          :key="user.username"
          :user="user.username"
          :theme="theme"
        />
      </ErrorBoundary>
      <ErrorBoundary v-if="view === 'semantic'">
        <SemanticView :key="user.username" :is-admin="isAdmin" />
      </ErrorBoundary>
      <ErrorBoundary v-if="view === 'eval' && isAdmin">
        <EvalView :key="user.username" :is-admin="isAdmin" />
      </ErrorBoundary>
      <ErrorBoundary v-if="view === 'audit'">
        <AuditView :key="user.username" :is-admin="isAdmin" />
      </ErrorBoundary>
    </main>

    <ChangePasswordDialog
      v-if="showPasswordDialog"
      @close="showPasswordDialog = false"
      @changed="onPasswordChanged"
    />
  </div>
</template>
