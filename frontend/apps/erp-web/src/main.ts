import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import '@ztx/design-tokens/theme.css'
import './styles.css'
const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')