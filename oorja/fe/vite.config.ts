import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  // Vercel serves from the domain root so base is always "/".
  base: "/",
  server: { port: 5173 }
});
