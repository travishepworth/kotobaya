// Settings screen state (/settings): study settings, presets, devices/pairing, API tokens, server status.
import qrcode from 'qrcode-generator';
import { Scope, type Device, type Meta, type Pairing, type Preset, type Token } from '../api/generated';
import { pushToast } from '../toasts.svelte';
import { devicesApi, metaApi, presetsApi, settingsApi, tokensApi } from '../settings/api';
import { parseSteps, secondsLeft, STUDY_SETTINGS, type SettingDef } from '../settings/keys';
import { ts, type SettingsMessageKey } from '../settings/messages';

export type { Device, Meta, Pairing, Preset, Token } from '../api/generated';
export { STUDY_SETTINGS } from '../settings/keys';
export type { SettingDef } from '../settings/keys';

/* ---------- study settings ---------- */

function settingDraft(def: SettingDef, v: unknown): string | boolean {
	const value = v === undefined || v === null ? def.default : v;
	return def.type === 'bool' ? value === true : String(value);
}

export class StudySettingsState {
	/** Draft values as form strings / booleans, keyed by setting key. */
	draft = $state<Record<string, string | boolean>>({});
	stored = $state<Record<string, unknown>>({});
	loaded = $state(false);
	busy = $state(false);

	private apply(items: { key: string; value: unknown }[]): void {
		const map: Record<string, unknown> = {};
		for (const i of items) map[i.key] = i.value;
		this.stored = map;
		const d: Record<string, string | boolean> = {};
		for (const def of STUDY_SETTINGS) d[def.key] = settingDraft(def, map[def.key]);
		this.draft = d;
	}

	async load(): Promise<void> {
		try {
			this.apply((await settingsApi.listSettings()).items);
			this.loaded = true;
		} catch {
			/* error toast already shown by the client */
		}
	}

	/** The value to send for a def, or undefined when unchanged; throws a message on bad input. */
	private toValue(def: SettingDef): unknown {
		const raw = this.draft[def.key];
		switch (def.type) {
			case 'bool':
				return raw === true;
			case 'string':
				return String(raw).trim();
			case 'buttons':
				return Number(raw) === 5 ? 5 : 4;
			case 'int': {
				const s = String(raw).trim();
				const n = Number(s);
				const bad =
					!/^-?\d+$/.test(s) || (def.min !== undefined && n < def.min) || (def.max !== undefined && n > def.max);
				if (bad) throw new Error(ts('settings.invalidNumber', { field: ts(def.label) }));
				return n;
			}
		}
	}

	async save(): Promise<void> {
		const values: Record<string, unknown> = {};
		try {
			for (const def of STUDY_SETTINGS) {
				const v = this.toValue(def);
				const before = this.stored[def.key] ?? def.default;
				if (v !== before) values[def.key] = v;
			}
		} catch (err) {
			pushToast(err instanceof Error ? err.message : String(err), 'error');
			return;
		}
		if (Object.keys(values).length === 0) return;
		this.busy = true;
		try {
			this.apply((await settingsApi.updateSettings({ settingsPatch: { values } })).items);
			pushToast(ts('settings.saved'), 'info');
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}

	async resetKey(def: SettingDef): Promise<void> {
		this.busy = true;
		try {
			this.apply((await settingsApi.updateSettings({ settingsPatch: { values: { [def.key]: null } } })).items);
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}
}

/* ---------- presets ---------- */

export const DEFAULT_PRESET_ID = '00000000-0000-7000-8000-000000000010';
export const DAYS: SettingsMessageKey[] = ['day.1', 'day.2', 'day.3', 'day.4', 'day.5', 'day.6', 'day.7'];

export interface PresetDraft {
	name: string;
	retention: string;
	maxInterval: string;
	learning: string;
	relearning: string;
	fuzz: boolean;
	easyDays: string[];
}

function presetDraft(p: Preset): PresetDraft {
	return {
		name: p.name,
		retention: String(p.desiredRetention),
		maxInterval: String(p.maximumIntervalDays),
		learning: p.learningStepsS.join(', '),
		relearning: p.relearningStepsS.join(', '),
		fuzz: p.fuzz,
		easyDays: p.easyDays.map(String)
	};
}

export class PresetsState {
	presets = $state<Preset[]>([]);
	drafts = $state<Record<string, PresetDraft>>({});
	busy = $state(false);

	async load(): Promise<void> {
		try {
			this.presets = (await presetsApi.listPresets({})).items;
			this.drafts = Object.fromEntries(this.presets.map((p) => [p.id, presetDraft(p)]));
		} catch {
			/* toast shown */
		}
	}

	async save(p: Preset): Promise<void> {
		const d = this.drafts[p.id];
		if (!d) return;
		const learning = parseSteps(d.learning);
		const relearning = parseSteps(d.relearning);
		if (!learning || !relearning) {
			pushToast(ts('presets.invalidSteps'), 'error');
			return;
		}
		this.busy = true;
		try {
			await presetsApi.updatePreset({
				presetId: p.id,
				presetPatch: {
					name: d.name.trim(),
					desiredRetention: Number(d.retention),
					maximumIntervalDays: Number(d.maxInterval),
					learningStepsS: learning,
					relearningStepsS: relearning,
					fuzz: d.fuzz,
					easyDays: d.easyDays.map(Number)
				}
			});
			pushToast(ts('settings.saved'), 'info');
			await this.load();
		} catch {
			/* 422 etc. shown as toast */
		} finally {
			this.busy = false;
		}
	}

	async add(): Promise<void> {
		this.busy = true;
		try {
			await presetsApi.createPreset({ presetCreate: { name: ts('presets.newName') } });
			await this.load();
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}

	/** Delete (the view asks for confirmation first). */
	async remove(p: Preset): Promise<void> {
		try {
			await presetsApi.deletePreset({ presetId: p.id });
			await this.load();
		} catch {
			/* toast shown */
		}
	}

	async optimize(p: Preset): Promise<void> {
		try {
			await presetsApi.optimizePreset({ presetId: p.id });
			pushToast(ts('presets.optimizeQueued'), 'info');
		} catch {
			/* toast shown */
		}
	}
}

/* ---------- devices ---------- */

/** Construct during component init (it runs the pairing countdown tick). */
export class DevicesState {
	devices = $state<Device[]>([]);
	pairing = $state<Pairing | null>(null);
	qrSvg = $state('');
	now = $state(new Date());
	renaming = $state<{ id: string; name: string } | null>(null);

	readonly left = $derived(this.pairing ? secondsLeft(this.pairing.expiresAt, this.now) : 0);
	readonly expired = $derived(this.pairing !== null && this.left === 0);

	constructor() {
		// Tick once a second while a code is shown, to drive the countdown.
		$effect(() => {
			if (!this.pairing) return;
			const id = setInterval(() => (this.now = new Date()), 1000);
			return () => clearInterval(id);
		});
	}

	async load(): Promise<void> {
		try {
			this.devices = (await devicesApi.listDevices()).items;
		} catch {
			/* toast shown */
		}
	}

	async startPairing(): Promise<void> {
		try {
			const p = await devicesApi.createPairing();
			const qr = qrcode(0, 'M');
			qr.addData(p.pairingUri);
			qr.make();
			this.qrSvg = qr.createSvgTag({ cellSize: 6, margin: 2, scalable: true });
			this.now = new Date();
			this.pairing = p;
		} catch {
			/* toast shown */
		}
	}

	/** Revoke (the view asks for confirmation first). */
	async revoke(d: Device): Promise<void> {
		try {
			await devicesApi.revokeDevice({ deviceId: d.id });
			await this.load();
		} catch {
			/* toast shown */
		}
	}

	beginRename(d: Device): void {
		this.renaming = { id: d.id, name: d.name };
	}

	async commitRename(): Promise<void> {
		const renaming = this.renaming;
		if (!renaming || renaming.name.trim() === '') return;
		try {
			await devicesApi.updateDevice({
				deviceId: renaming.id,
				devicePatch: { name: renaming.name.trim() }
			});
			this.renaming = null;
			await this.load();
		} catch {
			/* toast shown */
		}
	}
}

/* ---------- tokens ---------- */

export const SCOPES = [Scope.Read, Scope.Write, Scope.Review, Scope.Import, Scope.Admin] as const;

export class TokensState {
	tokens = $state<Token[]>([]);
	name = $state('');
	scopes = $state<Record<string, boolean>>({ read: true });
	expiry = $state('');
	withDevice = $state(false);
	busy = $state(false);
	/** The secret, held only while the dialog is open. */
	secret = $state<string | null>(null);

	async load(): Promise<void> {
		try {
			this.tokens = (await tokensApi.listTokens({})).items;
		} catch {
			/* toast shown */
		}
	}

	async create(): Promise<void> {
		const chosen = SCOPES.filter((s) => this.scopes[s]);
		if (this.name.trim() === '') return void pushToast(ts('tokens.needName'), 'error');
		if (chosen.length === 0) return void pushToast(ts('tokens.needScope'), 'error');
		let expiresAt: Date | undefined;
		if (this.expiry !== '') {
			expiresAt = new Date(this.expiry);
			if (Number.isNaN(expiresAt.getTime()) || expiresAt.getTime() <= Date.now()) {
				return void pushToast(ts('tokens.pastExpiry'), 'error');
			}
		}
		this.busy = true;
		try {
			const created = await tokensApi.createToken({
				tokenCreate: {
					name: this.name.trim(),
					scopes: new Set(chosen),
					...(expiresAt ? { expiresAt } : {}),
					withDevice: this.withDevice
				}
			});
			this.secret = created.token;
			this.name = '';
			this.expiry = '';
			this.withDevice = false;
			this.scopes = { read: true };
			await this.load();
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}

	async copy(): Promise<void> {
		if (this.secret === null) return;
		try {
			await navigator.clipboard.writeText(this.secret);
			pushToast(ts('tokens.copied'), 'info');
		} catch {
			/* clipboard unavailable: the user can still select the text */
		}
	}

	/** Forget the secret; it is never retrievable again. */
	forgetSecret(): void {
		this.secret = null;
	}

	/** Revoke (the view asks for confirmation first). */
	async revoke(tok: Token): Promise<void> {
		try {
			await tokensApi.revokeToken({ tokenId: tok.id });
			await this.load();
		} catch {
			/* toast shown */
		}
	}
}

/* ---------- server status ---------- */

export class ServerStatusState {
	meta = $state<Meta | null>(null);

	load(): void {
		metaApi
			.getMeta()
			.then((m) => (this.meta = m))
			.catch(() => {
				/* toast shown */
			});
	}
}
