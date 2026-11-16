// web/ has no @types/node (no new dependency, SHELL-A1): declare only the Node APIs the manifest script and its test use.
declare module 'node:crypto' {
	export function createHash(alg: string): { update(data: string | Uint8Array): { digest(enc: 'hex'): string } };
}
declare module 'node:child_process' {
	export function execSync(cmd: string, opts?: { stdio?: string[] }): { toString(): string };
}
declare module 'node:fs' {
	export function readdirSync(p: string): string[];
	export function readFileSync(p: string): Uint8Array;
	export function readFileSync(p: string, enc: 'utf8'): string;
	export function statSync(p: string): { isDirectory(): boolean };
	export function writeFileSync(p: string, data: string): void;
	export function mkdirSync(p: string, opts?: { recursive?: boolean }): void;
	export function mkdtempSync(prefix: string): string;
}
declare module 'node:os' {
	export function tmpdir(): string;
}
declare module 'node:path' {
	export const sep: string;
	export function join(...p: string[]): string;
	export function relative(from: string, to: string): string;
}
