import {GraphQLClient} from 'graphql-request';
import fetch from "node-fetch";

export async function waitMs(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

export async function getGraphQLClient(): Promise<GraphQLClient> {
  return new GraphQLClient(process.env['MERLIN_GRAPHQL_URL'] as string, {
    headers: {
      'x-hasura-admin-secret': process.env['HASURA_GRAPHQL_ADMIN_SECRET'] as string,
      'x-hasura-user-id': 'Aerie Legacy',
      'x-hasura-role': 'aerie_admin',
    },
  });
}

export async function loginTestUser() {
  const response = await fetch(`${process.env['MERLIN_GATEWAY_URL']}/auth/login`, {
    method: 'POST',
    body: `{"username": "AerieE2ESequencingTests", "password": "password"}`,
    headers: {'Content-Type': 'application/json'},
  });
  if (!response.ok) {
    throw new Error(`Failed to login: ${response.statusText}`);
  }
  return (await response.json() as { token: string }).token;
}
