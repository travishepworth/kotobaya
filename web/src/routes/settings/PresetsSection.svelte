<script lang="ts">
	// FSRS presets: each a collapsible group with retention, steps, Easy Days; optimise and delete.
	import Button from '../../lib/ui/Button.svelte';
	import Dialog from '../../lib/ui/Dialog.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import Switch from '../../lib/ui/Switch.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import { ut } from '../../lib/ui/messages';
	import { ts } from '../../lib/settings/messages';
	import { DAYS, DEFAULT_PRESET_ID, PresetsState, type Preset } from '../../lib/state/settings.svelte';
	import { sx } from './messages';

	const vm = new PresetsState();
	let loaded = $state(false);
	$effect(() => {
		void vm.load().then(() => (loaded = true));
	});
	let deleting = $state<Preset | null>(null);
	let open = $state<string | null>(DEFAULT_PRESET_ID);
</script>

{#if !loaded}
	<Skeleton shape="row" count={2} />
{:else}
	<div class="presets">
		{#each vm.presets as p (p.id)}
			{@const d = vm.drafts[p.id]}
			{#if d}
				<details class="preset" open={open === p.id} ontoggle={(e) => (open = (e.currentTarget as HTMLDetailsElement).open ? p.id : open === p.id ? null : open)}>
					<summary>
						<span class="name">{p.name}</span>
						{#if p.id === DEFAULT_PRESET_ID}<span class="tag">{sx('sx.default')}</span>{/if}
						<span class="meta">{sx('sx.retentionPct', { pct: Math.round(p.desiredRetention * 100) })} · {sx('sx.weights', { n: p.weightsVersion })}</span>
					</summary>
					<form
						class="body"
						onsubmit={(e) => {
							e.preventDefault();
							void vm.save(p);
						}}
					>
						<div class="grid">
							<TextField label={ts('presets.name')} bind:value={d.name} />
							<TextField label={ts('presets.retention')} inputmode="decimal" bind:value={d.retention} />
							<TextField label={ts('presets.maxInterval')} inputmode="numeric" bind:value={d.maxInterval} />
							<TextField label={ts('presets.learningSteps')} bind:value={d.learning} />
							<TextField label={ts('presets.relearningSteps')} bind:value={d.relearning} />
						</div>
						<Switch label={ts('presets.fuzz')} bind:checked={d.fuzz} />
						<fieldset class="days">
							<legend>{ts('presets.easyDays')}</legend>
							<div class="day-grid">
								{#each DAYS as day, i (day)}
									<TextField label={ts(day)} inputmode="decimal" value={d.easyDays[i] ?? ''} oninput={(e) => (d.easyDays[i] = (e.currentTarget as HTMLInputElement).value)} />
								{/each}
							</div>
						</fieldset>
						<p class="hint">{p.optimizedAt ? sx('sx.optimized', { when: p.optimizedAt.toLocaleDateString() }) : sx('sx.notOptimized')}</p>
						<div class="actions">
							{#if p.id !== DEFAULT_PRESET_ID}
								<Button variant="quiet" icon="trash" onclick={() => (deleting = p)}>{ts('presets.delete')}</Button>
							{/if}
							<span class="spacer"></span>
							<Button variant="outline" icon="sparkle" onclick={() => vm.optimize(p)}>{ts('presets.optimize')}</Button>
							<Button variant="primary" type="submit" loading={vm.busy}>{ut('app.save')}</Button>
						</div>
					</form>
				</details>
			{/if}
		{/each}
		<div><Button variant="tonal" icon="plus" loading={vm.busy} onclick={() => vm.add()}>{ts('presets.add')}</Button></div>
	</div>
{/if}

<Dialog open={deleting !== null} title={sx('sx.deletePreset', { name: deleting?.name ?? '' })} onclose={() => (deleting = null)}>
	<p>{ts('presets.confirmDelete', { name: deleting?.name ?? '' })}</p>
	{#snippet actions()}
		<Button variant="quiet" onclick={() => (deleting = null)}>{ut('app.cancel')}</Button>
		<Button
			variant="danger"
			onclick={() => {
				if (deleting) void vm.remove(deleting);
				deleting = null;
			}}>{ts('presets.delete')}</Button
		>
	{/snippet}
</Dialog>

<style>
	.presets {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
	}
	.preset {
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface);
	}
	summary {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-1) var(--kb-space-3);
		min-height: 3.25rem;
		padding: var(--kb-space-2) var(--kb-space-4);
		cursor: pointer;
		list-style: none;
		border-radius: var(--kb-radius-md);
	}
	summary::-webkit-details-marker {
		display: none;
	}
	summary::after {
		content: '';
		width: 0.5rem;
		height: 0.5rem;
		margin-inline-start: auto;
		border-right: 2px solid var(--kb-color-text-muted);
		border-bottom: 2px solid var(--kb-color-text-muted);
		transform: rotate(45deg);
		transition: transform var(--kb-duration-fast) var(--kb-ease-standard);
	}
	details[open] summary::after {
		transform: rotate(-135deg);
	}
	summary:focus-visible {
		outline: 2px solid var(--kb-color-focus);
	}
	.name {
		font: var(--kb-type-title-sm);
	}
	.tag {
		padding: 1px var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
		font: var(--kb-type-caption);
	}
	.meta {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.body {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
		padding: var(--kb-space-2) var(--kb-space-4) var(--kb-space-4);
		border-top: 1px solid var(--kb-color-border);
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr));
		gap: var(--kb-space-3);
		padding-top: var(--kb-space-3);
	}
	.days {
		margin: 0;
		padding: 0;
		border: 0;
	}
	legend {
		margin-bottom: var(--kb-space-2);
		font: var(--kb-type-label);
	}
	.day-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(4.5rem, 1fr));
		gap: var(--kb-space-2);
	}
	.hint {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
	}
	.spacer {
		flex: 1;
	}
</style>
