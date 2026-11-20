import fetch from 'node-fetch';
import { setTimeout as delay } from 'node:timers/promises';
import dotenv from 'dotenv';
import path from 'path';
import { fileURLToPath } from 'url';
import { loginTestUser } from "./test/testUtils/login.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

dotenv.config({ path: __dirname + '/./../.env' });
dotenv.config();

const gatewayUrl = process.env['MERLIN_GATEWAY_URL'] ??= 'http://localhost:9000';
const graphqlUrl = process.env['MERLIN_GRAPHQL_URL'] ??= 'http://localhost:8080/v1/graphql';
const merlinUrl = process.env['PLANDEV_MERLIN_URL'] ?? 'http://localhost:27183';
process.env['HASURA_GRAPHQL_ADMIN_SECRET'] ??= '';
process.env['AERIE_PASSWORD'] ??= '';
process.env['AERIE_USERNAME'] ??= '';

export default async () => {
  await Promise.all([
    waitForHealthy(`${gatewayUrl}/health`, 'Gateway'),
    waitForHealthy(`${graphqlUrl.replace(/(?:\/v1\/graphql)?\/?$/, '')}/healthz`, 'GraphQL API'),
    waitForHealthy(`${merlinUrl}/health`, 'Merlin'),
  ]);

  try {
    await loginTestUser();
  } catch (e) {
    throw new Error(`Failed to login as test user`, { cause: e });
  }
};

async function waitForHealthy(url, serviceName, timeout = 30000) {
  const deadline = Date.now() + timeout;
  let lastError;
  while (Date.now() < deadline) {
    try {
      // give each request 2 seconds to respond before aborting and retrying
      const reqTimeout = Math.min(2_000, deadline - Date.now());
      const response = await fetch(url, { signal: AbortSignal.timeout(Math.max(0, reqTimeout)) });
      await response.text();
      if (response.ok) return;
      lastError = new Error(`HTTP ${response.status} ${response.statusText}`);
    } catch (err) {
      lastError = err;
    }
    await delay(250);
  }
  throw new Error(`Timed out waiting for ${serviceName} at ${url}: ${lastError}`, { cause: lastError });
}
