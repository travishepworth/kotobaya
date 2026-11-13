// A background job followed live over the event stream (media import, jpdb history import).
import type { Job } from '../api/generated';
import { BlobsApi, JobsApi, PutBlobKindEnum } from '../api/generated';
import { apiConfig } from '../api/client';
import { openEventStream } from '../events';
import { sha256Hex } from '../../routes/import/counters';

export type { Job } from '../api/generated';

const jobsApi = new JobsApi(apiConfig);
const blobsApi = new BlobsApi(apiConfig);

export function isRunning(j: Job | null): boolean {
	return j !== null && (j.status === 'queued' || j.status === 'running');
}

/** Upload bytes as an `upload` blob; resolves to its sha256 hash. */
export async function putUploadBlob(bytes: ArrayBuffer, type: string): Promise<string> {
	const hash = await sha256Hex(bytes);
	await blobsApi.putBlob({
		kind: PutBlobKindEnum.Upload,
		hash,
		// The generated model named Blob shadows the DOM Blob in this parameter's type.
		body: new Blob([bytes], { type }) as never
	});
	return hash;
}

/** The job on screen plus its live subscription. Call `stop()` when the screen goes away. */
export class JobWatch {
	job = $state<Job | null>(null);
	private closeStream: (() => void) | null = null;

	stop(): void {
		this.closeStream?.();
		this.closeStream = null;
	}

	private async refresh(id: string): Promise<void> {
		try {
			this.job = await jobsApi.getJob({ jobId: id });
			if (!isRunning(this.job)) this.stop();
		} catch {
			// The API client already showed an error toast.
		}
	}

	/** Show `started` and, while it runs, follow its updates. */
	show(started: Job): void {
		this.job = started;
		if (isRunning(started)) this.follow(started.id);
	}

	private follow(id: string): void {
		this.stop();
		this.closeStream = openEventStream({
			types: ['job.updated'],
			onEvent: (e) => {
				if (e.type === 'job.updated' && e.data['id'] === id) void this.refresh(id);
			},
			// No replay after a reconnect: refetch the job.
			onReconnect: () => void this.refresh(id)
		});
	}

	clear(): void {
		this.stop();
		this.job = null;
	}
}
