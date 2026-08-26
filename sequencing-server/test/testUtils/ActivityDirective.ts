import { gql, GraphQLClient } from 'graphql-request';

export async function insertActivityDirective(
  graphqlClient: GraphQLClient,
  planId: number,
  activityType: string,
  startOffset: string = '30 seconds 0 milliseconds',
  args: any = {},
): Promise<number> {
  const res = await graphqlClient.request<{
    insert_activity_directive_one: { id: number };
  }>(
    gql`
      mutation InsertTestActivityDirective(
        $activityType: String!
        $planId: Int!
        $startOffset: interval!
        $arguments: jsonb
      ) {
        insert_activity_directive_one(
          object: { type: $activityType, start_offset: $startOffset, plan_id: $planId, arguments: $arguments }
        ) {
          id
        }
      }
    `,
    {
      planId: planId,
      activityType,
      startOffset: startOffset,
      arguments: args,
    },
  );
  return res.insert_activity_directive_one.id;
}

export async function removeActivityDirective(
  graphqlClient: GraphQLClient,
  activityId: number,
  planId: number,
): Promise<void> {
  return graphqlClient.request(
    gql`
      mutation DeleteActivityDirective($activityId: Int!, $planId: Int!) {
        delete_activity_directive_by_pk(id: $activityId, plan_id: $planId) {
          id
          plan_id
        }
      }
    `,
    {
      activityId,
      planId,
    },
  );
}
