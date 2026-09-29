import { createApp } from 'vue'
import App from './App.vue'
import './styles.css'
import { consumeDevLogin } from './lib/dev-login.js'

consumeDevLogin()
createApp(App).mount('#app')
