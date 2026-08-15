import { json } from 'lib/xhr';

import type { MistakeData, Result } from './interfaces';

/* One place holds the user visible name of this thing. Rename the route and
 * this constant and the feature is called something else. */
const base = '/mistakes';

// the window travels with every request: the server holds no per-user setting
const qs = (window: string) => `?window=${encodeURIComponent(window)}`;

export const next = (window: string): Promise<MistakeData> => json(`${base}/next${qs(window)}`);

export const record = (result: Result): Promise<{ game?: string }> =>
  json(`${base}/record/${result}`, { method: 'post' });

export const reset = (window: string): Promise<MistakeData> =>
  json(`${base}/reset${qs(window)}`, { method: 'post' });

export const homeUrl = (window: string): string => `${base}${qs(window)}`;
