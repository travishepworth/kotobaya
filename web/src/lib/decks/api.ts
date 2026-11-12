import { DecksApi } from '../api/generated';
import { apiConfig } from '../api/client';

export const decksApi = new DecksApi(apiConfig);
