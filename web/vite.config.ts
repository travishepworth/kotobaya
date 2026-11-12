import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vitest/config';

// Dev only: forward /api to a running server (compose or `kubectl port-forward`).
// The session cookie is Secure; browsers accept it on http://localhost.
const env = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env;
const apiTarget = env?.KOTOBAYA_API_URL ?? 'http://localhost:8080';

export default defineConfig({
	plugins: [
		sveltekit({
			adapter: adapter({ fallback: 'index.html' })
		})
	],
	server: {
		host: '0.0.0.0',
		port: 3000,
		fs: { allow: ['..'] },
		proxy: { '/api': { target: apiTarget, changeOrigin: true } }
	},
	preview: { host: '0.0.0.0', port: 3000 },
	test: { include: ['tests/**/*.test.ts'], environment: 'node' }
});
