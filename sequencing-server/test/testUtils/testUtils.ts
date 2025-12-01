import {GraphQLClient} from 'graphql-request';

export async function waitMs(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

export async function getGraphQLClient(): Promise<GraphQLClient> {
  return new GraphQLClient(process.env['MERLIN_GRAPHQL_URL'] as string, {
    headers: {
      'x-hasura-admin-secret': process.env['HASURA_GRAPHQL_ADMIN_SECRET'] as string,
      'x-hasura-user-id': 'PlanDev Legacy',
      'x-hasura-role': 'plandev_admin',
    },
  });
}
