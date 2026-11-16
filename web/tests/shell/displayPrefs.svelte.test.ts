// PORTRAIT-WEB: "Lock to portrait" (shell.yaml getDisplayPrefs / updateDisplayPrefs, capability `display`).
// The row is driven by DeviceState.display: read only when the shell lists the capability; toggling sends
// `{lock_portrait}` and reverts on failure.
import { beforeEach, describe, expect, it, vi } from 'vitest';

const shellApis = vi.hoisted(() => ({
	device: {
		getDisplayPrefs: vi.fn(),
		updateDisplayPrefs: vi.fn(),
		getDictInstall: vi.fn(),
		getStorage: vi.fn(),
		getNotificationState: vi.fn(),
		getPushState: vi.fn(),
		getAnkiConnectState: vi.fn()
	},
	caps: new Set<string>()
}));
vi.mock('../../src/lib/api/shell/client', () => ({ shellApi: { getSyncStatus: vi.fn() }, shellDeviceApi: shellApis.device }));
vi.mock('../../src/lib/api/shell', async (orig) => ({
	...(await orig<typeof import('../../src/lib/api/shell')>()),
	getShellTransport: () => null
}));
vi.mock('../../src/lib/state/shell.svelte', () => ({
	shell: { present: true, info: null, online: true, incompatible: false },
	has: (c: string) => shellApis.caps.has(c),
	refreshShellInfo: vi.fn()
}));

import { DeviceState } from '../../src/lib/state/device.svelte';

describe('lock to portrait (display capability)', () => {
	beforeEach(() => {
		vi.resetAllMocks();
		shellApis.caps.clear();
	});

	it('without the display capability nothing is read and the row has no state (hidden)', async () => {
		const vm = new DeviceState();
		await vm.load();
		expect(shellApis.device.getDisplayPrefs).not.toHaveBeenCalled();
		expect(vm.display).toBeNull();
	});

	it('with it: loads getDisplayPrefs; toggling calls updateDisplayPrefs({lock_portrait})', async () => {
		shellApis.caps.add('display');
		shellApis.device.getDisplayPrefs.mockResolvedValue({ lockPortrait: false });
		shellApis.device.updateDisplayPrefs.mockImplementation(async (r: { shellDisplayPatch: { lockPortrait: boolean } }) => ({
			lockPortrait: r.shellDisplayPatch.lockPortrait
		}));
		const vm = new DeviceState();
		await vm.load();
		expect(vm.display).toEqual({ lockPortrait: false });
		expect(await vm.setLockPortrait(true)).toBe(true);
		expect(shellApis.device.updateDisplayPrefs).toHaveBeenCalledWith({ shellDisplayPatch: { lockPortrait: true } });
		expect(vm.display).toEqual({ lockPortrait: true });
	});

	it('a refused update reverts the toggle', async () => {
		shellApis.caps.add('display');
		shellApis.device.getDisplayPrefs.mockResolvedValue({ lockPortrait: false });
		let reject!: (e: Error) => void;
		shellApis.device.updateDisplayPrefs.mockImplementation(() => new Promise((_, r) => (reject = r)));
		const vm = new DeviceState();
		await vm.load();
		const saving = vm.setLockPortrait(true);
		expect(vm.display).toEqual({ lockPortrait: true }); // the switch moves at once
		reject(new Error('503 shell_rebuilding'));
		expect(await saving).toBe(false);
		expect(vm.display).toEqual({ lockPortrait: false });
	});
});
