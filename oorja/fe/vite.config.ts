import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  // In CI (GitHub Actions) VITE_BASE is set to /oorja_code/ so GitHub Pages
  // serves assets from the correct sub-path. Locally it stays at "/" so dev
  // server and vite preview work without any changes.
  base: process.env.VITE_BASE ?? "/",
  server: { port: 5173 }
});
