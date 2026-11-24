import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
 testDir:'./e2e',fullyParallel:true,reporter:[['list'],['html',{open:'never'}]],
 use:{baseURL:'http://127.0.0.1:5173',trace:'retain-on-failure',screenshot:'only-on-failure'},
 projects:[{name:'chromium',use:{...devices['Desktop Chrome']}},{name:'mobile-chrome',use:{...devices['Pixel 7']}}],
 webServer:{command:'pnpm dev',url:'http://127.0.0.1:5173',reuseExistingServer:!process.env.CI,timeout:120000}
})