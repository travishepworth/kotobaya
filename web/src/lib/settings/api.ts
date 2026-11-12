import { DevicesApi, MetaApi, PresetsApi, SettingsApi, TokensApi } from '../api/generated';
import { apiConfig } from '../api/client';

export const settingsApi = new SettingsApi(apiConfig);
export const presetsApi = new PresetsApi(apiConfig);
export const devicesApi = new DevicesApi(apiConfig);
export const tokensApi = new TokensApi(apiConfig);
export const metaApi = new MetaApi(apiConfig);
