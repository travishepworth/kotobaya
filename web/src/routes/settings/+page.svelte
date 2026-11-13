<script lang="ts">
	// Settings: a section index on the left (desktop) and grouped panels; one column on phones.
	import Icon from '../../lib/ui/Icon.svelte';
	import Panel from '../../lib/ui/Panel.svelte';
	import Page from '../../lib/ui/Page.svelte';
	import SegmentedControl from '../../lib/ui/SegmentedControl.svelte';
	import type { IconName } from '../../lib/ui/icons';
	import { ut } from '../../lib/ui/messages';
	import { setTheme, theme, type ThemePreference } from '../../lib/ui/theme.svelte';
	import { ts } from '../../lib/settings/messages';
	import { ServerStatusState } from '../../lib/state/settings.svelte';
	import DevicesSection from './DevicesSection.svelte';
	import PresetsSection from './PresetsSection.svelte';
	import StudySection from './StudySection.svelte';
	import TokensSection from './TokensSection.svelte';
	import { sx, type SettingsViewKey } from './messages';

	const server = new ServerStatusState();
	$effect(() => {
		server.load();
	});

	const SECTIONS: { id: string; title: SettingsViewKey; icon: IconName }[] = [
		{ id: 'appearance', title: 'sx.appearance', icon: 'sun' },
		{ id: 'study', title: 'sx.study', icon: 'review' },
		{ id: 'scheduling', title: 'sx.presets', icon: 'clock' },
		{ id: 'devices', title: 'sx.devices', icon: 'sync' },
		{ id: 'tokens', title: 'sx.tokens', icon: 'settings' },
		{ id: 'server', title: 'sx.server', icon: 'system' },
		{ id: 'about', title: 'sx.about', icon: 'info' }
	];
	let pref = $state<ThemePreference>(theme.preference);
</script>

<svelte:head><title>{ts('settings.title')} · Kotobaya</title></svelte:head>

<Page title={ts('settings.title')}>
	<div class="frame">
	<div class="layout">
		<nav class="index" aria-label={sx('sx.sections')}>
			<ul>
				{#each SECTIONS as s (s.id)}
					<li><a href="#{s.id}"><Icon name={s.icon} size={18} />{sx(s.title)}</a></li>
				{/each}
			</ul>
		</nav>
		<div class="sections">
			<section id="appearance">
				<Panel title={sx('sx.appearance')} subtitle={sx('sx.appearanceLead')}>
					<SegmentedControl
						label={ut('ui.theme')}
						bind:value={pref}
						onchange={(v) => setTheme(v)}
						options={[
							{ value: 'system', label: ut('ui.theme.system'), icon: 'system' },
							{ value: 'light', label: ut('ui.theme.light'), icon: 'sun' },
							{ value: 'dark', label: ut('ui.theme.dark'), icon: 'moon' }
						]}
					/>
				</Panel>
			</section>
			<section id="study"><Panel title={sx('sx.study')} subtitle={sx('sx.studyLead')}><StudySection /></Panel></section>
			<section id="scheduling"><Panel title={sx('sx.presets')} subtitle={sx('sx.presetsLead')}><PresetsSection /></Panel></section>
			<section id="devices"><Panel title={sx('sx.devices')} subtitle={sx('sx.devicesLead')}><DevicesSection /></Panel></section>
			<section id="tokens"><Panel title={sx('sx.tokens')} subtitle={sx('sx.tokensLead')}><TokensSection /></Panel></section>
			<section id="server">
				<Panel title={ts('status.title')}>
					{#if server.meta}
						<dl class="facts">
							<div><dt>{ts('status.serverVersion')}</dt><dd>{server.meta.serverVersion}</dd></div>
							<div><dt>{ts('status.dictRelease')}</dt><dd>{server.meta.dictRelease?.version ?? ts('status.noDict')}</dd></div>
							<div><dt>{ts('status.highWater')}</dt><dd class="kb-num">{server.meta.highWaterSeq.toLocaleString()}</dd></div>
							<div><dt>{ts('status.serverTime')}</dt><dd>{server.meta.serverTime.toLocaleString()}</dd></div>
						</dl>
					{/if}
					<p class="hint">{ts('status.backupsUnknown')}</p>
				</Panel>
			</section>
			<section id="about">
				<Panel title={sx('sx.about')} subtitle={sx('sx.aboutLead')} link={{ href: '/settings/attribution', label: ts('settings.attribution') }}>
					<p class="hint">{ts('attr.jmdict')}</p>
				</Panel>
			</section>
		</div>
	</div>
	</div>
</Page>

<style>
	.frame {
		container-type: inline-size;
	}
	.layout {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: var(--kb-space-6);
	}
	.index {
		display: none;
	}
	.sections {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-5);
		min-width: 0;
		max-width: 50rem;
	}
	section {
		scroll-margin-top: var(--kb-space-6);
	}
	/* The section index appears once there is room for it next to a full-width panel (in rem: follows the text size). */
	@container (min-width: 56rem) {
		.layout {
			grid-template-columns: 13rem minmax(0, 1fr);
		}
		.index {
			display: block;
			position: sticky;
			top: var(--kb-space-8);
			align-self: start;
		}
	}
	.index ul {
		display: flex;
		flex-direction: column;
		gap: 2px;
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.index a {
		display: flex;
		align-items: center;
		gap: var(--kb-space-3);
		min-height: var(--kb-size-touch-min);
		padding: 0 var(--kb-space-3);
		border-radius: var(--kb-radius-md);
		color: var(--kb-color-text-muted);
		font: var(--kb-type-label);
		text-decoration: none;
	}
	.index a:hover {
		background: var(--kb-color-surface-hover);
		color: var(--kb-color-text);
	}
	.facts {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 12rem), 1fr));
		gap: var(--kb-space-3);
		margin: 0 0 var(--kb-space-3);
	}
	dt {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	dd {
		margin: 0;
		overflow-wrap: anywhere;
	}
	.hint {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
</style>
