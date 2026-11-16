// This device (Android shell): sync status, dictionary install, offline storage, notifications, push and
// AnkiConnect. Reads and writes go through the generated shell client only. Inert outside the shell.
import { DictionaryApi } from '../api/generated';
import { apiConfig } from '../api/client';
import { getShellTransport } from '../api/shell';
import { shellApi, shellDeviceApi } from '../api/shell/client';
import {
	ShellSyncStatusFromJSON,
	type ShellAnkiConnectState,
	type ShellDictInstall,
	type ShellDictInstallRequestScopeEnum,
	type ShellDisplay,
	type ShellNotificationState,
	type ShellPairRequest,
	type ShellPushState,
	type ShellStorage,
	type ShellStoragePatch,
	type ShellSyncStatus
} from '../api/shell/generated';
import { has, refreshShellInfo, shell } from './shell.svelte';

const dictApi = new DictionaryApi(apiConfig);

/** The shell's sync status; null outside the shell and until the first read. Fed by `shell.sync` events. */
export const syncStatus = $state<{ value: ShellSyncStatus | null }>({ value: null });

let syncWatching = false;

export async function readSyncStatus(): Promise<void> {
	if (!shell.present) return;
	try {
		syncStatus.value = await shellApi.getSyncStatus();
	} catch {
		/* unpaired or rebuilding: no status yet */
	}
}

/** Follow `shell.sync` events and read `getSyncStatus` once. Idempotent; inert outside the shell. */
export function watchSyncStatus(): void {
	const transport = getShellTransport();
	if (!transport || syncWatching) return;
	syncWatching = true;
	transport.subscribe((m) => {
		if (m.t !== 'event' || m.event.type !== 'shell.sync') return;
		try {
			syncStatus.value = ShellSyncStatusFromJSON(m.event.data);
		} catch {
			/* a malformed event: the next one or the next read fixes it */
		}
	});
	void readSyncStatus();
}

/** Start a sync cycle now. Resolves false when the shell refused (offline, unpaired). */
export async function syncNow(): Promise<boolean> {
	try {
		syncStatus.value = await shellApi.syncNow();
		return true;
	} catch {
		return false;
	}
}

export class DeviceState {
	loaded = $state(false);
	failed = $state(false);
	dict = $state<ShellDictInstall | null>(null);
	storage = $state<ShellStorage | null>(null);
	notifications = $state<ShellNotificationState | null>(null);
	push = $state<ShellPushState | null>(null);
	anki = $state<ShellAnkiConnectState | null>(null);
	/** Display preferences (capability `display`; device-local, never synced). */
	display = $state<ShellDisplay | null>(null);
	/** Download size per scope from the server's release artifacts (full artifacts); empty when unknown. */
	sizes = $state<Partial<Record<ShellDictInstallRequestScopeEnum, number>>>({});
	busy = $state(false);

	private watching = false;

	async load(): Promise<void> {
		const run = async <T,>(capability: string, f: () => Promise<T>, set: (v: T) => void): Promise<void> => {
			if (!has(capability)) return;
			try {
				set(await f());
			} catch {
				this.failed = true;
			}
		};
		await Promise.all([
			readSyncStatus(),
			run('dict_install', () => shellDeviceApi.getDictInstall(), (v) => (this.dict = v)),
			run('offline_storage', () => shellDeviceApi.getStorage(), (v) => (this.storage = v)),
			run('notifications', () => shellDeviceApi.getNotificationState(), (v) => (this.notifications = v)),
			run('push', () => shellDeviceApi.getPushState(), (v) => (this.push = v)),
			run('ankiconnect', () => shellDeviceApi.getAnkiConnectState(), (v) => (this.anki = v)),
			run('display', () => shellDeviceApi.getDisplayPrefs(), (v) => (this.display = v))
		]);
		this.loaded = true;
		void this.loadSizes();
		this.watch();
	}

	/** Package sizes come from `getDictRelease` (local_or_proxy); a failure just hides the sizes. */
	private async loadSizes(): Promise<void> {
		if (!has('dict_install')) return;
		try {
			const r = await dictApi.getDictRelease();
			const sizes: Partial<Record<ShellDictInstallRequestScopeEnum, number>> = {};
			for (const a of r.artifacts) if (a.kind === 'full') sizes[a.scope] = a.sizeBytes;
			this.sizes = sizes;
		} catch {
			this.sizes = {};
		}
	}

	private watch(): void {
		if (this.watching) return;
		this.watching = true;
		getShellTransport()?.subscribe((m) => {
			if (m.t !== 'event' || m.event.type !== 'shell.dict') return;
			void shellDeviceApi.getDictInstall().then(
				(v) => (this.dict = v),
				() => {}
			);
		});
	}

	async installDict(scope: ShellDictInstallRequestScopeEnum): Promise<boolean> {
		try {
			this.dict = await shellDeviceApi.startDictInstall({ shellDictInstallRequest: { scope } });
			return true;
		} catch {
			return false;
		}
	}

	async patchStorage(patch: ShellStoragePatch): Promise<void> {
		try {
			this.storage = await shellDeviceApi.updateStorage({ shellStoragePatch: patch });
		} catch {
			this.failed = true;
		}
	}

	async patchNotifications(patch: { nudgesEnabled?: boolean; nudgeThreshold?: number }): Promise<void> {
		try {
			this.notifications = await shellDeviceApi.updateNotificationPrefs({ shellNotificationPrefsPatch: patch });
		} catch {
			this.failed = true;
		}
	}

	async requestNotifications(): Promise<void> {
		try {
			this.notifications = await shellDeviceApi.requestNotificationPermission();
		} catch {
			this.failed = true;
		}
	}

	async setPush(enabled: boolean): Promise<void> {
		try {
			this.push = await shellDeviceApi.updatePushState({ shellPushPatch: { enabled } });
		} catch {
			this.failed = true;
		}
	}

	async setAnki(enabled: boolean): Promise<void> {
		try {
			this.anki = await shellDeviceApi.updateAnkiConnectState({ shellAnkiConnectPatch: { enabled } });
		} catch {
			this.failed = true;
		}
	}

	/**
	 * Lock to portrait (shell.yaml `updateDisplayPrefs`): the switch moves at once and goes back if the shell
	 * refuses. Resolves whether it was saved.
	 */
	async setLockPortrait(lockPortrait: boolean): Promise<boolean> {
		const before = this.display;
		this.display = { lockPortrait };
		try {
			this.display = await shellDeviceApi.updateDisplayPrefs({ shellDisplayPatch: { lockPortrait } });
			return true;
		} catch {
			this.display = before;
			return false;
		}
	}

	/** Unpair (`force` when ops are pending); re-reads ShellInfo. */
	async unpair(force: boolean): Promise<boolean> {
		this.busy = true;
		try {
			await shellApi.unpairShell({ shellUnpairRequest: { force } });
			await refreshShellInfo();
			return true;
		} catch {
			return false;
		} finally {
			this.busy = false;
		}
	}
}

export type DictScope = ShellDictInstallRequestScopeEnum;
export type PairRequest = ShellPairRequest;
export type PairOutcome = 'ok' | 'invalid' | 'unreachable' | 'rate_limited' | 'already' | 'failed';

/** Pair (or re-pair) this phone. A retry after a lost response (409 already_exists) counts as success when paired. */
export async function pairDevice(body: ShellPairRequest): Promise<PairOutcome> {
	try {
		await shellApi.pairShell({ shellPairRequest: body });
	} catch (err) {
		const response = (err as { response?: Response }).response;
		const status = response?.status ?? 0;
		let code = '';
		try {
			code = ((await response?.clone().json()) as { code?: string } | undefined)?.code ?? '';
		} catch {
			/* not a problem body */
		}
		if (status === 409 && code === 'already_exists') {
			await refreshShellInfo();
			if (!shell.info?.paired) return 'already';
		} else if (status === 503 || status === 0) return 'unreachable';
		else if (status === 401 || code === 'invalid_pairing_code') return 'invalid';
		else if (status === 429) return 'rate_limited';
		else return 'failed';
	}
	await refreshShellInfo();
	return 'ok';
}
