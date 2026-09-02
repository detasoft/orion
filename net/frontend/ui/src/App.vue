<script setup>
import { computed, onMounted, ref } from 'vue'
import AppIcon from './components/AppIcon.vue'
import { createOrionClient, formatRelativeDate, normalizeRepositoryName } from './lib/orion-api.js'
import { loadConnectionSettings, saveConnectionSettings } from './lib/connection-store.js'

const navItems = [
  { id: 'overview', label: 'Overview', icon: 'overview' },
  { id: 'repositories', label: 'Repositories', icon: 'repository' },
  { id: 'people', label: 'People', icon: 'users' },
  { id: 'activity', label: 'Activity', icon: 'activity' },
]

const activeView = ref('overview')
const search = ref('')
const darkMode = ref(false)
const sidebarOpen = ref(false)
const createOpen = ref(false)
const settingsOpen = ref(false)
const submitting = ref(false)
const connectionState = ref('disconnected')
const serverSnapshot = ref({ lifecycle: '', routes: [], transports: {} })
const createdRepositories = ref([])
const connectedActivity = ref([])
const toast = ref(null)
const newRepository = ref({ name: '' })
const settings = ref({ sshUsername: '', token: '' })
const settingsDraft = ref({ sshUsername: '', token: '' })
const draftConnectionState = ref('disconnected')

let toastTimer
let api = createOrionClient()
let connectionAttempt = 0
let draftConnectionAttempt = 0

const titles = {
  overview: ['Overview', 'A quiet view of everything happening in Orion.'],
  repositories: ['Repositories', 'Browse and manage source repositories.'],
  people: ['People', 'Manage members and repository access.'],
  activity: ['Activity', 'The latest changes across your server.'],
}

const currentTitle = computed(() => titles[activeView.value] ?? titles.overview)
const isConnected = computed(() => connectionState.value === 'connected')
const shownRepositories = computed(() => createdRepositories.value)
const shownActivity = computed(() => connectedActivity.value)
const filteredRepositories = computed(() => {
  const query = search.value.trim().toLowerCase()
  if (!query) return shownRepositories.value
  return shownRepositories.value.filter((repository) => {
    return repository.name.toLowerCase().includes(query)
      || repository.language.toLowerCase().includes(query)
  })
})

const serverLabel = computed(() => {
  if (connectionState.value === 'connected') return 'Connected'
  if (connectionState.value === 'checking') return 'Checking'
  if (connectionState.value === 'error') return 'Offline'
  return 'Not connected'
})

const serverName = computed(() => window.location.hostname)
const draftServerLabel = computed(() => {
  if (draftConnectionState.value === 'connected') return 'Connection verified'
  if (draftConnectionState.value === 'checking') return 'Checking'
  if (draftConnectionState.value === 'error') return 'Connection failed'
  return 'Not tested'
})

function selectView(view) {
  activeView.value = view
  search.value = ''
  sidebarOpen.value = false
}

function showToast(message, kind = 'success') {
  clearTimeout(toastTimer)
  toast.value = { message, kind }
  toastTimer = setTimeout(() => {
    toast.value = null
  }, 3400)
}

function openSettings() {
  settingsDraft.value = { ...settings.value }
  draftConnectionState.value = 'disconnected'
  settingsOpen.value = true
  sidebarOpen.value = false
}

function closeSettings() {
  draftConnectionAttempt += 1
  settingsDraft.value = { ...settings.value }
  draftConnectionState.value = 'disconnected'
  settingsOpen.value = false
}

function clearConnectedState(nextState = 'disconnected') {
  serverSnapshot.value = { lifecycle: '', routes: [], transports: {} }
  createdRepositories.value = []
  connectedActivity.value = []
  connectionState.value = nextState
}

function clearExpiredCredentials() {
  settings.value = { ...settings.value, token: '' }
  saveConnectionSettings(settings.value)
  api = createOrionClient()
  clearConnectedState()
}

function isAuthorizationError(error) {
  return error?.status === 401 || error?.status === 403
}

function cloneUrls(repository) {
  const transports = serverSnapshot.value.transports
  const name = encodeRepositoryPath(repository.name)

  const urls = [
    cloneUrl('SSH', transports.ssh, `/${name}.git`),
    cloneUrl('HTTP', transports.http, `/r/${name}`),
    cloneUrl('HTTPS', transports.https, `/r/${name}`),
    cloneUrl('Native', transports.nativeGit, `/${name}`),
  ].filter(Boolean)
  const seen = new Set()
  return urls.filter((cloneUrl) => {
    if (seen.has(cloneUrl.url)) return false
    seen.add(cloneUrl.url)
    return true
  })
}

function encodeRepositoryPath(name) {
  return name.split('/').map(encodeURIComponent).join('/')
}

function cloneUrl(label, transport, path) {
  if (!transport?.enabled || !transport.url) return null

  try {
    const url = new URL(transport.url)
    if (label === 'SSH') {
      const username = settings.value.sshUsername.trim()
      if (!username) return null
      url.username = username
    }
    url.pathname = path
    url.search = ''
    url.hash = ''
    return {
      label: labelForProtocol(url.protocol, label),
      url: url.toString(),
      requiresBearerToken: url.protocol === 'http:' || url.protocol === 'https:',
    }
  } catch {
    return null
  }
}

function labelForProtocol(protocol, fallback) {
  return ({ 'git:': 'Native', 'http:': 'HTTP', 'https:': 'HTTPS', 'ssh:': 'SSH' })[protocol]
    ?? fallback
}

async function copyCloneUrl(cloneUrl) {
  try {
    if (!navigator.clipboard?.writeText) {
      throw new Error('Clipboard is not available')
    }
    const value = cloneUrl.requiresBearerToken
      ? authenticatedCloneCommand(cloneUrl.url)
      : cloneUrl.url
    await navigator.clipboard.writeText(value)
    showToast(cloneUrl.requiresBearerToken ? 'Token-free clone command copied' : 'Clone URL copied')
  } catch (error) {
    showToast(error.message || 'Could not copy clone URL', 'error')
  }
}

function authenticatedCloneCommand(url) {
  return `git --config-env=http.extraHeader=ORION_AUTH_HEADER clone "${url}"`
}

async function createRepository() {
  const name = normalizeRepositoryName(newRepository.value.name)
  if (!name) {
    showToast('Repository name is required', 'error')
    return
  }
  if (!isConnected.value) {
    showToast('Connect to Orion before creating a repository', 'error')
    return
  }

  submitting.value = true
  try {
    const response = await api.createRepository(name)
    if (response.created === false) {
      createOpen.value = false
      showToast('Repository already exists')
      return
    }
    const repository = {
      name,
      language: '—',
      color: '#a8b0a9',
      updated: new Date(),
      size: '0 KB',
    }
    const event = {
      actor: 'You',
      action: 'created repository',
      target: name,
      time: 'just now',
      type: 'create',
    }
    createdRepositories.value.unshift(repository)
    connectedActivity.value.unshift(event)
    createOpen.value = false
    newRepository.value = { name: '' }
    showToast('Repository created')
  } catch (error) {
    if (isAuthorizationError(error)) {
      clearExpiredCredentials()
      showToast('Your Admin token is no longer valid. Connect again.', 'error')
      return
    }
    showToast(error.message, 'error')
  } finally {
    submitting.value = false
  }
}

async function testConnection() {
  const attempt = ++draftConnectionAttempt
  const token = settingsDraft.value.token.trim()
  draftConnectionState.value = 'checking'
  const candidate = createOrionClient({ token })
  try {
    await Promise.all([
      candidate.routes(),
      candidate.lifecycleState(),
      candidate.transports(),
    ])
    if (attempt === draftConnectionAttempt) {
      draftConnectionState.value = 'connected'
      showToast('Connection verified')
    }
  } catch (error) {
    if (attempt === draftConnectionAttempt) {
      draftConnectionState.value = 'error'
      showToast(error.message || 'Could not connect to Orion', 'error')
    }
  }
}

async function saveSettings() {
  connectionAttempt += 1
  draftConnectionAttempt += 1
  settings.value = {
    sshUsername: settingsDraft.value.sshUsername.trim(),
    token: settingsDraft.value.token.trim(),
  }
  saveConnectionSettings(settings.value)
  api = createOrionClient({ token: settings.value.token })
  settingsOpen.value = false
  clearConnectedState()
  if (settings.value.token) {
    await connectSavedSettings()
    return
  }
  showToast('Connection settings saved')
}

async function connectSavedSettings() {
  const attempt = ++connectionAttempt
  connectionState.value = 'checking'
  try {
    const [routes, lifecycle, transports] = await Promise.all([
      api.routes(),
      api.lifecycleState(),
      api.transports(),
    ])
    if (attempt === connectionAttempt) {
      serverSnapshot.value = { lifecycle, routes: routes.routes ?? [], transports }
      connectionState.value = 'connected'
      showToast('Connected to Orion')
    }
  } catch (error) {
    if (attempt !== connectionAttempt) return
    if (isAuthorizationError(error)) {
      clearExpiredCredentials()
      showToast('Your Admin token is no longer valid. Connect again.', 'error')
      return
    }
    clearConnectedState('error')
    showToast(error.message || 'Could not connect to Orion', 'error')
  }
}

function toggleTheme() {
  darkMode.value = !darkMode.value
  localStorage.setItem('orion.ui.theme', darkMode.value ? 'dark' : 'light')
}

onMounted(() => {
  darkMode.value = localStorage.getItem('orion.ui.theme') === 'dark'
  settings.value = loadConnectionSettings()
  api = createOrionClient({ token: settings.value.token })
  if (settings.value.token) {
    connectSavedSettings()
  }
})
</script>

<template>
  <div class="app-shell" :class="{ 'dark-mode': darkMode }">
    <div v-if="sidebarOpen" class="scrim" @click="sidebarOpen = false" />

    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <div class="brand">
        <span class="brand-mark"><span /></span>
        <span class="brand-name">orion</span>
      </div>

      <nav class="primary-nav" aria-label="Primary navigation">
        <p class="nav-label">Workspace</p>
        <button
          v-for="item in navItems"
          :key="item.id"
          class="nav-item"
          :class="{ active: activeView === item.id }"
          @click="selectView(item.id)"
        >
          <AppIcon :name="item.icon" :size="19" />
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <div class="sidebar-bottom">
        <button class="nav-item" @click="openSettings">
          <AppIcon name="settings" :size="19" />
          <span>Settings</span>
        </button>
        <button type="button" class="server-card" @click="openSettings">
          <span class="server-icon"><AppIcon name="server" :size="17" /></span>
          <span>
            <strong>{{ serverName }}</strong>
            <small><i :class="connectionState" />{{ serverLabel }}</small>
          </span>
          <AppIcon name="chevron" :size="16" />
        </button>
      </div>
    </aside>

    <main class="main-content">
      <header class="topbar">
        <button class="icon-button menu-button" aria-label="Open navigation" @click="sidebarOpen = true">
          <AppIcon name="menu" />
        </button>
        <label v-if="activeView === 'repositories'" class="search-box">
          <AppIcon name="search" :size="18" />
          <input v-model="search" type="search" placeholder="Search repositories…" />
        </label>
        <div class="topbar-actions">
          <button class="icon-button" aria-label="Toggle theme" @click="toggleTheme">
            <AppIcon :name="darkMode ? 'sun' : 'moon'" :size="18" />
          </button>
          <button
            class="primary-button compact"
            aria-label="New repository"
            :disabled="!isConnected"
            @click="createOpen = true"
          >
            <AppIcon name="plus" :size="18" />
            <span>New repository</span>
          </button>
        </div>
      </header>

      <div class="page">
        <section class="page-heading">
          <div>
            <span class="eyebrow">Orion workspace</span>
            <h1>{{ currentTitle[0] }}</h1>
            <p>{{ currentTitle[1] }}</p>
          </div>
        </section>

        <template v-if="activeView === 'overview'">
          <template v-if="isConnected">
            <section class="metrics-grid connected-metrics">
              <article class="metric-card metric-featured">
                <div class="metric-top">
                  <span class="metric-icon"><AppIcon name="server" :size="19" /></span>
                </div>
                <p>Registered routes</p>
                <strong>{{ serverSnapshot.routes.length }}</strong>
                <span class="metric-caption">Reported by this Orion server</span>
              </article>
              <article class="metric-card">
                <div class="metric-top">
                  <span class="metric-icon blue"><AppIcon name="activity" :size="19" /></span>
                </div>
                <p>Runtime state</p>
                <strong class="state-value">{{ serverSnapshot.lifecycle || 'Unknown' }}</strong>
                <span class="metric-caption">Live lifecycle response</span>
              </article>
            </section>
            <section class="panel connected-panel">
              <div class="panel-heading">
                <div>
                  <h2>Server routes</h2>
                  <p>Verified from the connected Orion instance</p>
                </div>
              </div>
              <div class="route-list">
                <div v-for="route in serverSnapshot.routes" :key="route.urlPattern" class="route-row">
                  <code>{{ route.urlPattern }}</code><span>{{ route.methods.join(', ') }}</span>
                  <span>{{ route.authorization }}</span>
                </div>
              </div>
            </section>
            <section class="panel api-limit-panel">
              <AppIcon name="repository" :size="19" />
              <div>
                <h2>Repository and member lists are not exposed by the current Admin API.</h2>
                <p>Only repositories created in this browser session appear in the Repositories view.</p>
              </div>
            </section>
          </template>
          <template v-else>
            <section class="panel connection-empty">
              <span class="modal-icon"><AppIcon name="server" :size="23" /></span>
              <h2>Connect to an Orion server</h2>
              <p>Server data is shown only after the Admin API connection has been verified.</p>
              <button class="primary-button" @click="openSettings">
                <AppIcon name="server" :size="17" />Connect to Orion
              </button>
            </section>
          </template>
        </template>

        <section v-else-if="activeView === 'repositories'" class="panel content-panel">
          <div class="content-toolbar">
            <p>
              {{ filteredRepositories.length }} repositories
              <span v-if="isConnected">created in this session</span>
            </p>
            <button class="primary-button" :disabled="!isConnected" @click="createOpen = true">
              <AppIcon name="plus" :size="17" />New repository
            </button>
          </div>
          <div class="repository-list expanded">
            <div v-for="repository in filteredRepositories" :key="repository.name" class="repository-row">
              <span class="repo-icon"><AppIcon name="repository" :size="19" /></span>
              <div class="repo-name">
                <strong>{{ repository.name }}</strong>
                <span><i :style="{ background: repository.color }" />{{ repository.language }}</span>
                <div class="clone-url-list" aria-label="Clone URLs">
                  <div v-for="cloneUrl in cloneUrls(repository)" :key="cloneUrl.url" class="clone-url">
                    <b>{{ cloneUrl.label }}</b>
                    <code>{{ cloneUrl.url }}</code>
                    <button
                      class="icon-button subtle"
                      :aria-label="cloneUrl.requiresBearerToken
                        ? `Copy token-free ${cloneUrl.label} clone command`
                        : `Copy ${cloneUrl.label} clone URL`"
                      @click="copyCloneUrl(cloneUrl)"
                    >
                      <AppIcon name="copy" :size="14" />
                    </button>
                  </div>
                </div>
                <p v-if="cloneUrls(repository).some((url) => url.requiresBearerToken)" class="clone-note">
                  HTTP(S): securely set `ORION_AUTH_HEADER` to `Authorization: Bearer …`, then copy
                  the token-free command. It works in POSIX shells and PowerShell; cmd.exe is not
                  supported for encoded repository paths.
                </p>
              </div>
              <span class="repo-update">Updated {{ formatRelativeDate(repository.updated) }}</span>
              <span class="repo-size">{{ repository.size }}</span>
            </div>
            <div v-if="!filteredRepositories.length" class="empty-state">
              <AppIcon name="search" :size="28" />
              <h3>{{ isConnected ? 'No repositories created in this session' : 'Connect to Orion first' }}</h3>
              <p>
                {{ isConnected
                  ? 'The current Admin API does not list existing repositories.'
                  : 'No repository data is stored in the interface.' }}
              </p>
            </div>
          </div>
        </section>

        <section v-else-if="activeView === 'people'" class="people-grid">
          <div class="empty-state panel">
            <AppIcon name="users" :size="28" />
            <h3>{{ isConnected ? 'Member listing is unavailable' : 'Connect to Orion first' }}</h3>
            <p>
              {{ isConnected
                ? 'The connected Admin API accepts user updates but does not expose a user list.'
                : 'No member data is stored in the interface.' }}
            </p>
          </div>
        </section>

        <section v-else class="panel content-panel activity-page">
          <div
            v-for="event in shownActivity"
            :key="`${event.target}-${event.time}`"
            class="activity-row large"
          >
            <span class="event-icon" :class="event.type">
              <AppIcon :name="event.type === 'access' ? 'lock' : 'git-branch'" :size="18" />
            </span>
            <p>
              <strong>{{ event.actor }}</strong> {{ event.action }} <b>{{ event.target }}</b>
              <small>{{ event.time }}</small>
            </p>
          </div>
          <div v-if="!shownActivity.length" class="empty-state">
            <AppIcon name="activity" :size="28" />
            <h3>{{ isConnected ? 'No activity data is exposed by this API' : 'Connect to Orion first' }}</h3>
            <p>
              {{ isConnected
                ? 'New repositories created in this session will be recorded here.'
                : 'No activity data is stored in the interface.' }}
            </p>
          </div>
        </section>
      </div>
    </main>

    <div v-if="createOpen" class="modal-layer" @mousedown.self="createOpen = false">
      <form
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="create-repository-title"
        @keydown.esc="createOpen = false"
        @submit.prevent="createRepository"
      >
        <button
          type="button"
          class="icon-button close-button"
          aria-label="Close"
          @click="createOpen = false"
        >
          <AppIcon name="close" />
        </button>
        <span class="modal-icon"><AppIcon name="repository" :size="23" /></span>
        <h2 id="create-repository-title">Create a repository</h2>
        <p>Start a new project on your Orion server.</p>
        <label>
          Repository name<input v-model="newRepository.name" autofocus placeholder="team/project" />
        </label>
        <div class="modal-actions">
          <button type="button" class="secondary-button" @click="createOpen = false">Cancel</button>
          <button class="primary-button" :disabled="submitting">
            <span v-if="submitting" class="spinner" />
            {{ submitting ? 'Creating…' : 'Create repository' }}
          </button>
        </div>
      </form>
    </div>

    <div v-if="settingsOpen" class="modal-layer" @mousedown.self="closeSettings">
      <form
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="settings-title"
        @keydown.esc="closeSettings"
        @submit.prevent="saveSettings"
      >
        <button
          type="button"
          class="icon-button close-button"
          aria-label="Close"
          @click="closeSettings"
        >
          <AppIcon name="close" />
        </button>
        <span class="modal-icon"><AppIcon name="server" :size="23" /></span>
        <h2 id="settings-title">Connect to Orion</h2>
        <p>Use the current Orion server and an Admin API token to view verified server data.</p>
        <label>
          Admin token
          <input v-model="settingsDraft.token" autofocus type="password" placeholder="Bearer token" />
        </label>
        <label>
          SSH username<input v-model="settingsDraft.sshUsername" placeholder="Your Orion username" />
        </label>
        <div class="connection-result" :class="draftConnectionState">
          <i /><span>{{ draftServerLabel }}</span>
        </div>
        <div class="modal-actions split">
          <button
            type="button"
            class="secondary-button"
            :disabled="draftConnectionState === 'checking'"
            @click="testConnection"
          >
            Test connection
          </button>
          <button class="primary-button" :disabled="draftConnectionState === 'checking'">Save settings</button>
        </div>
      </form>
    </div>

    <Transition name="toast">
      <div v-if="toast" class="toast" :class="toast.kind" role="status" aria-live="polite">
        <span><AppIcon :name="toast.kind === 'error' ? 'close' : 'check'" :size="16" /></span>
        {{ toast.message }}
      </div>
    </Transition>
  </div>
</template>
