import { config } from 'dotenv';
import fetch from 'node-fetch';
import dotenv from 'dotenv';
import path from 'path';
import { fileURLToPath } from 'url';
import { loginTestUser } from "./test/testUtils/login.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

dotenv.config({ path: __dirname + '/./../.env' });
config();

process.env.MERLIN_GATEWAY_URL = process.env.MERLIN_GATEWAY_URL ?? 'http://localhost:9000';
process.env.MERLIN_GRAPHQL_URL = process.env.MERLIN_GRAPHQL_URL ?? 'http://localhost:8080/v1/graphql';
process.env.HASURA_GRAPHQL_ADMIN_SECRET = process.env.HASURA_GRAPHQL_ADMIN_SECRET ?? '';
process.env.PLANDEV_PASSWORD = process.env.PLANDEV_PASSWORD ?? '';
process.env.PLANDEV_USERNAME = process.env.PLANDEV_USERNAME ?? '';

export default async () => {
  try {
    await fetchWithTimeout(`${process.env.MERLIN_GATEWAY_URL}/health`);
  } catch (e) {
    throw new Error(`Merlin Gateway is not running at ${process.env.MERLIN_GATEWAY_URL}`, { cause: e });
  }

  try {
    await fetchWithTimeout(`${process.env.MERLIN_GRAPHQL_URL.replace('/v1/graphql', '')}/healthz`);
  } catch (e) {
    throw new Error(`Merlin GraphQL API is not running at ${process.env.MERLIN_GRAPHQL_URL}`, { cause: e });
  }

  try {
    await loginTestUser();
  } catch (e) {
    throw new Error(`Failed to login as test user`, { cause: e });
  }
};

async function fetchWithTimeout(resource, options = {}) {
  const { timeout = 2000 } = options;

  const controller = new AbortController();
  const id = setTimeout(() => controller.abort(), timeout);
  const response = await fetch(resource, {
    ...options,
    signal: controller.signal,
  });
  clearTimeout(id);
  return response;
}
