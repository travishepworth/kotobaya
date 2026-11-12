import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vitest/config';

export default defineConfig({
	plugins: [sveltekit({ adapter: adapter({ fallback: 'index.html' }) })],
	server: { host: '0.0.0.0', port: 3000, fs: { allow: ['..'] } },
	preview: { host: '0.0.0.0', port: 3000 },
	test: { include: ['tests/**/*.test.ts'], environment: 'node' }
});
