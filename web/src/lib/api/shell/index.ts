// Shell mode (ADR-036, SHELL.md 9.3): detected at run time by the presence of `window.KotobayaShell`, never by a
// build flag. Everything here is inert in a normal browser.
import { MIN_LOCAL_API } from './protocol';
import type { BridgeEvent } from './protocol';
import { ShellTransport } from './transport';
import type { ShellPort } from './transport';

declare global {
	interface Window {
		KotobayaShell?: ShellPort;
	}
}

const APP_VERSION: string = import.meta.env?.VITE_APP_VERSION || '0.0.0-dev';

function port(): ShellPort | undefined {
	return typeof window === 'undefined' ? undefined : (window.KotobayaShell ?? undefined);
}

/** True inside the Android shell's WebView. */
export function isShellMode(): boolean {
	return port() !== undefined;
}

let transport: ShellTransport | null = null;
let hello: BridgeEvent | null = null;

/** The page's one bridge endpoint; null outside the shell. */
export function getShellTransport(): ShellTransport | null {
	if (transport) return transport;
	const p = port();
	if (!p) return null;
	transport = new ShellTransport(p);
	transport.subscribe((m) => {
		if (m.t === 'event' && m.event.type === 'hello') hello = m.event;
	});
	return transport;
}

/** The `hello` of this page life (sent once, in answer to `ready`); null until it arrived. */
export function lastHello(): BridgeEvent | null {
	return hello;
}

/** `fetch` for both generated clients in shell mode (`Configuration.fetchApi`). */
export const shellFetch: typeof fetch = (input, init) => {
	const t = getShellTransport();
	if (!t) return Promise.reject(new TypeError('shellFetch: not running in the Android shell'));
	return t.fetch(input, init);
};

let readySent = false;

/**
 * Send `ready` (SHELL.md 2.5), at most once per page life. Native answers with the `hello` event and then delivers
 * pending navigation; it sends no event before this.
 */
export function sendReady(): void {
	const t = getShellTransport();
	if (!t || readySent) return;
	readySent = true;
	const webVersion = /^[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}$/.test(APP_VERSION) ? APP_VERSION : '0.0.0-dev';
	t.send({ v: 1, t: 'ready', web_version: webVersion, min_local_api: MIN_LOCAL_API });
}

export { MIN_LOCAL_API };
export type { ShellPort };
