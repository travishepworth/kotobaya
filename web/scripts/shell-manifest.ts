/// <reference path="./shell-manifest.node.d.ts" />
import { createHash } from 'node:crypto';
import { execSync } from 'node:child_process';
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

// ADR-036: the Android shell bundles the SPA and checks it against this manifest.
export const MANIFEST_NAME = 'shell-manifest.json';
export const MIN_LOCAL_API = 2;

export interface ShellManifest {
	web_version: string;
	build_sha: string;
	min_local_api: number;
	files: Record<string, string>;
}

function walk(dir: string, out: string[]): void {
	for (const name of readdirSync(dir).sort()) {
		const p = join(dir, name);
		if (statSync(p).isDirectory()) walk(p, out);
		else out.push(p);
	}
}

/** Every file under `dir` (except the manifest itself) → sha256 hex, keyed by '/'-separated relative path. */
export function hashFiles(dir: string): Record<string, string> {
	const paths: string[] = [];
	walk(dir, paths);
	const files: Record<string, string> = {};
	for (const p of paths) {
		const rel = relative(dir, p).split(sep).join('/');
		if (rel === MANIFEST_NAME) continue;
		files[rel] = createHash('sha256').update(readFileSync(p)).digest('hex');
	}
	return files;
}

export function buildManifest(dir: string, webVersion: string, buildSha: string): ShellManifest {
	return { web_version: webVersion, build_sha: buildSha, min_local_api: MIN_LOCAL_API, files: hashFiles(dir) };
}

export function gitShortSha(): string {
	try {
		return execSync('git rev-parse --short HEAD', { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim() || 'dev';
	} catch {
		return 'dev';
	}
}

export function writeShellManifest(dir: string, packageJsonPath: string): void {
	const pkg = JSON.parse(readFileSync(packageJsonPath, 'utf8')) as { version: string };
	const manifest = buildManifest(dir, pkg.version, gitShortSha());
	writeFileSync(join(dir, MANIFEST_NAME), JSON.stringify(manifest, null, 2) + '\n');
}
