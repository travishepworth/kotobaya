// Settings screen strings added by the rebuild; the rest come from lib/settings/messages.
const en = {
	'sx.sections': 'Sections',
	'sx.appearance': 'Appearance',
	'sx.appearanceLead': 'Follows your system unless you pick one. Saved in this browser.',
	'sx.study': 'Study',
	'sx.studyLead': 'When your day starts, how much comes each day, and how you grade.',
	'sx.day': 'Your day',
	'sx.limits': 'Daily limits',
	'sx.grading': 'Grading',
	'sx.queue': 'Queue order',
	'sx.other': 'Audio',
	'sx.resetOne': 'Reset {field} to its default',
	'sx.presets': 'Scheduling',
	'sx.presetsLead': 'FSRS presets: how hard cards should be to remember and how far apart reviews may grow.',
	'sx.devices': 'Sync and devices',
	'sx.devicesLead': 'Phones and scripts paired with this server.',
	'sx.tokens': 'API tokens',
	'sx.tokensLead': 'Personal tokens for scripts and integrations.',
	'sx.server': 'Server',
	'sx.about': 'About',
	'sx.aboutLead': 'Kotobaya stands on open data and open software.',
	'sx.retentionPct': '{pct}% recall',
	'sx.steps': 'Steps',
	'sx.easyDays': 'Easy Days',
	'sx.default': 'Default',
	'sx.weights': 'Weights v{n}',
	'sx.optimized': 'Optimised {when}',
	'sx.notOptimized': 'Not optimised yet',
	'sx.deletePreset': 'Delete “{name}”?',
	'sx.revokeTitle': 'Revoke “{name}”?',
	'sx.secretTitle': 'Your new token',
	'sx.noDevices': 'No devices paired yet',
	'sx.noDevicesBody': 'Pair the Android app to review on your phone, offline too.',
	'sx.noTokens': 'No tokens yet',
	'sx.noTokensBody': 'Create one for AnkiConnect-style tools or your own scripts.',
	'sx.newToken': 'New token',
	'sx.saveStudy': 'Save study settings',
	'sx.kind': '{kind} · {prefix}…',
	'sx.loadFailed': 'Settings could not be loaded.',
	'sx.yes': 'On',
	'sx.no': 'Off'
} as const;

export type SettingsViewKey = keyof typeof en;

export function sx(key: SettingsViewKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
