// SPDX-License-Identifier: Apache-2.0
import { resolveApiEndpoint } from '../api/http';

export async function resolveBootApiBase(): Promise<string | null> {
  const endpoint = await resolveApiEndpoint();
  return endpoint.baseUrl;
}
