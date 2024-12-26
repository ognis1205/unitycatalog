import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "./build",
  },
  resolve: {
    alias: [
      { find: /^~/, replacement: '' },
    ],
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
