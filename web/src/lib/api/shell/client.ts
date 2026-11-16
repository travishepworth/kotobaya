// The generated shell client (shell.yaml, `/shell/v1`): only usable in shell mode, where it travels over the bridge.
import { Configuration, DeviceApi, ShellApi } from './generated';
import { shellFetch } from './index';

export const SHELL_BASE = '/shell/v1';

/** No credentials, no CSRF header: the bridge is origin-restricted and the shell holds the token (SHELL.md 9.1). */
export const shellApiConfig = new Configuration({ basePath: SHELL_BASE, fetchApi: shellFetch });

export const shellApi = new ShellApi(shellApiConfig);
export const shellDeviceApi = new DeviceApi(shellApiConfig);
