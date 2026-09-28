import { defineConfig } from 'vite'
import legacy from '@vitejs/plugin-legacy'

// TVs load the app from file:// (Samsung packaged app) or old Chromium (~53-56 on 2018 sets), where
// ES-module scripts either do not exist or are blocked for a null origin. So ONLY the legacy
// (SystemJS, ES5) build is emitted, and every TV takes the same path.
export default defineConfig({
  base: './',
  plugins: [
    legacy({ targets: ['chrome >= 47'], renderModernChunks: false }),
    // `crossorigin` turns a plain script load into a CORS request, which a file:// page fails.
    { name: 'strip-crossorigin', enforce: 'post', transformIndexHtml: (html) => html.replace(/ crossorigin/g, '') },
  ],
  build: { outDir: 'dist', assetsInlineLimit: 0 },
})
