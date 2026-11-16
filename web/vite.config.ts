import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vitest/config';
import { demoApi } from './mock/demo-api.ts';
import { writeShellManifest } from './scripts/shell-manifest.ts';

// Dev only: forward /api to a running server (compose or `kubectl port-forward`).
// The session cookie is Secure; browsers accept it on http://localhost.
const env = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env;
const apiTarget = env?.KOTOBAYA_API_URL ?? 'http://localhost:8080';
// Mock-server mode (`make dev-ui`): KOTOBAYA_MOCK=<scenario> answers /api from fixtures/demo instead of a server.
const mockScenario = env?.KOTOBAYA_MOCK;
// Under vitest only: component tests mount Svelte components, which needs Svelte's browser build (not the server build).
const underVitest = env?.VITEST !== undefined;

// ADR-036: after adapter-static has written build/, add build/shell-manifest.json for the Android shell.
const staticAdapter = adapter({ fallback: 'index.html' });
const shellAdapter = {
	...staticAdapter,
	async adapt(builder: Parameters<typeof staticAdapter.adapt>[0]) {
		await staticAdapter.adapt(builder);
		writeShellManifest('build', 'package.json');
	}
};

export default defineConfig({
	plugins: [
		...(mockScenario ? [demoApi(mockScenario)] : []),
		sveltekit({
			adapter: shellAdapter
		})
	],
	resolve: underVitest ? { conditions: ['browser'] } : {},
	server: {
		host: '0.0.0.0',
		port: 3000,
		fs: { allow: ['..'] },
		proxy: mockScenario ? {} : { '/api': { target: apiTarget, changeOrigin: true } }
	},
	preview: { host: '0.0.0.0', port: 3000 },
	test: { include: ['tests/**/*.test.ts'], environment: 'node' }
});
