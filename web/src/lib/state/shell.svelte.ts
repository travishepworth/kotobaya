// Shell state (ADR-036, SHELL.md 9.3): whether the SPA runs in the Android shell, what the shell offers, and
// connectivity. The UI gates features on a capability (`has`) or an operation's class (`modeOf`), never on
// "is Android". Inert in a normal browser: present = false, online = true, no capabilities.
import { getShellTransport, isShellMode, MIN_LOCAL_API, sendReady } from '../api/shell';
import { shellApi } from '../api/shell/client';
import { ShellSyncStatusFromJSON, type ShellSyncStatusFirstSync, type ShellInfo } from '../api/shell/generated';
import { opClass } from '../api/shell/ops';
import type { OpClass } from '../api/shell/ops';

export type { OpClass };

export const shell = $state<{
	/** Running inside the Android shell. */
	present: boolean;
	/** `getShellInfo`; null until loaded (and always outside the shell). */
	info: ShellInfo | null;
	/** The device reports a usable network; kept fresh by `shell.connectivity`. Always true in a browser. */
	online: boolean;
	/** The shell's LOCAL_API_VERSION is below this bundle's min_local_api: show "update the app" (SHELL.md 7.2). */
	incompatible: boolean;
	/** `ShellSyncStatus.first_sync`; null until read (and always outside the shell). Fed by `shell.sync`. */
	firstSync: ShellSyncStatusFirstSync | null;
	/** Latched once `first_sync.done` was seen: the setup screen is never shown again in this page life. */
	firstSyncDone: boolean;
}>({ present: isShellMode(), info: null, online: true, incompatible: false, firstSync: null, firstSyncDone: false });

/** Whether the shell offers a capability (`ShellInfo.capabilities`). False outside the shell. */
export function has(capability: string): boolean {
	return shell.info?.capabilities.has(capability) ?? false;
}

/** How an operation of openapi.yaml is answered on the phone (ops.json); undefined for an unknown id. */
export function modeOf(operationId: string): OpClass | undefined {
	return opClass(operationId);
}

/** SHELL.md 10.18: the full-screen "Setting up" state shows while the first sync has not completed. */
export function isFirstSyncPending(): boolean {
	return shell.present && !shell.firstSyncDone && shell.firstSync !== null && !shell.firstSync.done;
}

/** Record a `first_sync` value; `done` latches. */
export function applyFirstSync(fs: ShellSyncStatusFirstSync): void {
	shell.firstSync = fs;
	if (fs.done) shell.firstSyncDone = true;
}

/** Apply the payload of a `shell.sync` event. A malformed payload is ignored; the next event or read fixes it. */
export function applySyncEvent(data: Record<string, unknown>): void {
	try {
		const fs: ShellSyncStatusFirstSync | undefined = ShellSyncStatusFromJSON(data).firstSync;
		if (fs && typeof fs.done === 'boolean') applyFirstSync(fs);
	} catch {
		/* ignored */
	}
}

/** Read `getSyncStatus` for `first_sync`. Fails quietly (unpaired, rebuilding): no value means no setup screen. */
export async function refreshFirstSync(): Promise<void> {
	if (!shell.present) return;
	try {
		applyFirstSync((await shellApi.getSyncStatus()).firstSync);
	} catch {
		/* keep the previous value */
	}
}

/** Re-read `getShellInfo` (pairing, auth_required, capabilities, online). No-op outside the shell. */
export async function refreshShellInfo(): Promise<void> {
	if (!shell.present) return;
	try {
		const info = await shellApi.getShellInfo();
		shell.info = info;
		shell.online = info.online;
		shell.incompatible = info.localApiVersion < MIN_LOCAL_API;
		void refreshFirstSync();
	} catch {
		/* keep the previous state; the next refresh or event fixes it */
	}
}

let started = false;

/**
 * Start shell mode once per page life: follow connectivity and sync events, send `ready`, load
 * `getShellInfo` and `first_sync`. Safe to call repeatedly and outside the shell.
 */
export function initShell(): Promise<void> {
	const transport = getShellTransport();
	if (!transport || started) return Promise.resolve();
	started = true;
	transport.subscribe((m) => {
		if (m.t !== 'event') return;
		const { type, data } = m.event;
		if (type === 'shell.connectivity' && typeof data.online === 'boolean') shell.online = data.online;
		else if (type === 'shell.sync') applySyncEvent(data);
		else if (type === 'hello') void refreshShellInfo();
	});
	sendReady();
	return refreshShellInfo();
}
