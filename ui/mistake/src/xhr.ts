import { json } from 'lib/xhr';

import type { MistakeData, Result } from './interfaces';

/* One place holds the user visible name of this thing. Rename the route and
 * this constant and the feature is called something else. */
const base = '/mistakes';

export const next = (): Promise<MistakeData> => json(`${base}/next`);

export const record = (result: Result): Promise<{ game?: string }> =>
  json(`${base}/record/${result}`, { method: 'post' });

export const reset = (): Promise<MistakeData> => json(`${base}/reset`, { method: 'post' });
