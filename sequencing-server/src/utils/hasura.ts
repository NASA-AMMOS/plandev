import jwtDecode from 'jwt-decode';

import type { AuthSessionVariables, Jwt } from '../types/jwtAuthentication';
import { gql, type GraphQLClient } from 'graphql-request';

enum HasuraPermissions {
  NO_CHECK = 'NO_CHECK',
  MISSION_MODEL_OWNER = 'MISSION_MODEL_OWNER',
  OWNER = 'OWNER',
  PLAN_OWNER = 'PLAN_OWNER',
  PLAN_COLLABORATOR = 'PLAN_COLLABORATOR',
  PLAN_OWNER_COLLABORATOR = 'PLAN_OWNER_COLLABORATOR',
}

const HASURA_PLAN_PERMISSIONS = [
  HasuraPermissions.OWNER,
  HasuraPermissions.PLAN_COLLABORATOR,
  HasuraPermissions.PLAN_OWNER,
  HasuraPermissions.PLAN_OWNER_COLLABORATOR,
];

/**
 * Endpoints that don't need any permission checking.
 */
export const ENDPOINTS_WHITELIST = new Set([
  '/',
  '/health',
  '/get-command-typescript',
  '/get-activity-typescript',
  '/put-dictionary',
  '/seqjson/bulk-get-seqjson-for-sequence-standalone',
  '/seqjson/get-seqjson-for-sequence-standalone',
  '/seqjson/get-seqjson-for-seqid-and-simulation-dataset',
  '/seqjson/bulk-get-edsl-for-seqjson',
  '/seqjson/get-edsl-for-seqjson',
]);

/**
 * Mapping of Sequencing endpoints to their DB action check. Any new endpoints need
 * to be evaulated and added here if they need fine-grained permissions.
 */
const ENDPOINTS_TO_ACTION_KEY: Record<string, string> = {
  '/command-expansion/put-expansion-set': 'create_expansion_set',
  '/command-expansion/put-expansion': 'create_expansion_rule',
  '/command-expansion/expand-all-activity-instances': 'expand_all_activities',
  '/command-expansion/expand-all-sequence-templates': 'expand_all_templates',
  '/command-expansion/assign-activities-by-filter': 'assign_activities_by_filter',
  '/seqjson/bulk-get-seqjson-for-seqid-and-simulation-dataset': 'sequence_seq_json_bulk',
};

/**
 * A helper function to get the Hasura session from the user who sent the request.
 *
 * @param authSessionVariables If the request has gone through a hasura action this object will be populated.
 * @param authHeader If the user sends a POST request directly to the endpoint with a bearer token this will be populated.
 * @returns The AuthSessionVeriables of the user sending the request.
 */
export function getHasuraSession(
  authSessionVariables: AuthSessionVariables | undefined,
  authHeader: string | undefined,
): AuthSessionVariables {
  if (authSessionVariables !== undefined) {
    return authSessionVariables;
  } else if (authHeader !== undefined) {
    return (jwtDecode(authHeader) as Jwt)['https://hasura.io/jwt/claims'];
  }

  throw new Error('Could not determine the user sending the request');
}

/**
 * A helper function to get the username of the user who sent the request.
 *
 * @param authSessionVariables If the request has gone through a hasura action this object will be populated.
 * @param authHeader If the user sends a POST request directly to the endpoint with a bearer token this will be populated.
 * @returns The username of the user sending the request.
 */
export function getUsername(
  authSessionVariables: AuthSessionVariables | undefined,
  authHeader: string | undefined,
): string {
  if (authSessionVariables !== undefined) {
    return authSessionVariables['x-hasura-user-id'];
  } else if (authHeader !== undefined) {
    return (jwtDecode(authHeader) as Jwt).username;
  }

  throw new Error('Could not determine the user sending the request');
}

/**
 * This function checks to see if request coming from the Hasura action is being executed
 * by a user with enough permissions. To check this we need to call a GQL query with the
 * action key and the user session. If we ever merge the databases this should be changed
 * to call the db function that does the permission checking directly.
 *
 * Some endpoints don't need to be checked which is why we have the mappings of endpoints
 * to actions to check.
 *
 * @param url The endpoint that we're checking the permissions for.
 * @param graphqlClient The GQL Client we're using to make the permission check.
 * @returns True if the user is able to call the given action, false otherwise.
 */
export async function canUserPerformAction(
  url: string,
  graphqlClient: GraphQLClient,
  hasuraSession: AuthSessionVariables,
  body: any,
): Promise<boolean> {
  const role = hasuraSession['x-hasura-role'];
  const username = hasuraSession['x-hasura-user-id'];

  // The aerie_admin role always has NO_CHECK permissions on all actions.
  if (role === 'aerie_admin') {
    return true;
  }

  const permissionCheckQuery = await graphqlClient.request(
    gql`
      query checkUserPermissions($role: user_roles_enum!, $key: String!) {
        user_role_permission_by_pk(role: $role) {
          action_permissions(path: $key)
        }
      }
    `,
    {
      role,
      key: ENDPOINTS_TO_ACTION_KEY[url],
    },
  );

  const permission = permissionCheckQuery.user_role_permission_by_pk.action_permissions as HasuraPermissions | null;

  if (permission === null) {
    return false;
  }

  const simulationDatasetId = body.input.simulationDatasetId as number | undefined;
  let missionModelId = body.input.missionModelId as number | undefined;
  let planId = body.input.planId as number | undefined;

  // If we have a simulationDatasetId and we need to check a plan permission, get the planId.
  if (simulationDatasetId !== undefined && HASURA_PLAN_PERMISSIONS.includes(permission)) {
    planId = await getPlanId(graphqlClient, simulationDatasetId);
  }

  switch (permission) {
    case HasuraPermissions.NO_CHECK:
      return true;
    case HasuraPermissions.MISSION_MODEL_OWNER:
      if (missionModelId === undefined) {
        missionModelId = await getMissionModelId(graphqlClient, planId, simulationDatasetId);
      }

      return await isMissionModelOwner(graphqlClient, username, missionModelId);
    case HasuraPermissions.OWNER:
      // Owner has 2 different meanings, either owner of the mission model or owner of the plan.
      if (missionModelId !== undefined) {
        return await isMissionModelOwner(graphqlClient, username, missionModelId);
      } else if (planId !== undefined) {
        return await isPlanOwner(graphqlClient, username, planId);
      }

      throw new Error(`Missing parameters, could not determine ${HasuraPermissions.OWNER} permission`);
    case HasuraPermissions.PLAN_OWNER:
      return await isPlanOwner(graphqlClient, username, planId, missionModelId);
    case HasuraPermissions.PLAN_COLLABORATOR:
      return await isPlanCollaborator(graphqlClient, username, planId, missionModelId);
    case HasuraPermissions.PLAN_OWNER_COLLABORATOR:
      return (
        (await isPlanOwner(graphqlClient, username, planId, missionModelId)) ||
        (await isPlanCollaborator(graphqlClient, username, planId, missionModelId))
      );
  }
}

async function isMissionModelOwner(
  graphqlClient: GraphQLClient,
  username: string,
  missionModelId: number,
): Promise<boolean> {
  const missionModelOwner = await graphqlClient.request<{
    mission_model_by_pk: { owner: string };
  }>(
    gql`
      query missionModelOwner($missionModelId: Int!) {
        mission_model_by_pk(id: $missionModelId) {
          owner
        }
      }
    `,
    { missionModelId },
  );

  return username === missionModelOwner.mission_model_by_pk?.owner;
}

async function isPlanOwner(
  graphqlClient: GraphQLClient,
  username: string,
  planId?: number,
  missionModelId?: number,
): Promise<boolean> {
  if (planId !== undefined) {
    const planOwner = await graphqlClient.request<{
      plan_by_pk: { owner: string };
    }>(
      gql`
        query planOwner($planId: Int!) {
          plan_by_pk(id: $planId) {
            owner
          }
        }
      `,
      { planId },
    );

    return username === planOwner.plan_by_pk?.owner;
  } else if (missionModelId !== undefined) {
    const planOwner = await graphqlClient.request<{
      mission_model_by_pk: { plans: { name: string }[] };
    }>(
      gql`
        query planOwner($missionModelId: Int!, $username: String!) {
          mission_model_by_pk(id: $missionModelId) {
            plans(where: { owner: { _eq: $username } }, limit: 1) {
              name
            }
          }
        }
      `,
      { missionModelId, username },
    );

    // planOwner.mission_model_by_pk will be null in the case of an invalid missionModelId being passed.
    if (planOwner.mission_model_by_pk !== null) {
      return planOwner.mission_model_by_pk.plans.length > 0;
    }
  }

  throw new Error(
    `Could not determine the ${HasuraPermissions.PLAN_OWNER} based on the provided ${
      planId ? 'planId' : 'missionModelId'
    }`,
  );
}

async function isPlanCollaborator(
  graphqlClient: GraphQLClient,
  username: string,
  planId?: number,
  missionModelId?: number,
): Promise<boolean> {
  if (planId !== undefined) {
    const planCollaborator = await graphqlClient.request<{
      plan_collaborators_by_pk: { collaborator: string | null };
    }>(
      gql`
        query planCollaborator($planId: Int!, $username: String!) {
          plan_collaborators_by_pk(plan_id: $planId, collaborator: $username) {
            collaborator
          }
        }
      `,
      { planId, username },
    );

    return planCollaborator !== null;
  } else if (missionModelId !== undefined) {
    const planCollaborator = await graphqlClient.request<{
      mission_model_by_pk: { plans: { collaborators: { collaborator: string | null } }[] };
    }>(
      gql`
        query planCollaborator($missionModelId: Int!, $username: String!) {
          mission_model_by_pk(id: $missionModelId) {
            plans(where: { collaborators: { collaborator: { _eq: $username } } }, limit: 1) {
              name
            }
          }
        }
      `,
      { missionModelId, username },
    );

    if (planCollaborator.mission_model_by_pk !== null) {
      return planCollaborator.mission_model_by_pk.plans.length > 0;
    }
  }

  throw new Error(
    `Could not determine the ${HasuraPermissions.PLAN_COLLABORATOR} based on the provided ${
      planId ? 'planId' : 'missionModelId'
    }`,
  );
}

async function getPlanId(graphqlClient: GraphQLClient, simulationDatasetId: number): Promise<number> {
  const planId = (
    await graphqlClient.request<{
      simulation_dataset_by_pk: { simulation: { plan_id: number } };
    }>(
      gql`
        query plan($simulationDatasetId: Int!) {
          simulation_dataset_by_pk(id: $simulationDatasetId) {
            simulation {
              plan_id
            }
          }
        }
      `,
      { simulationDatasetId },
    )
  ).simulation_dataset_by_pk.simulation.plan_id;

  if (planId === null || planId === undefined) {
    throw new Error('Could not determine the plan based on the provided simulationDatasetId');
  }

  return planId;
}

async function getMissionModelId(
  graphqlClient: GraphQLClient,
  planId?: number,
  simulationDatasetId?: number,
): Promise<number> {
  let missionModelId;

  if (planId !== undefined) {
    missionModelId = (
      await graphqlClient.request<{
        plan_by_pk: { model_id: number }[];
      }>(
        gql`
          query mission_model($planId: Int!) {
            plan_by_pk(id: $planId) {
              model_id
            }
          }
        `,
        { planId },
      )
    ).plan_by_pk[0]?.model_id;
  } else if (simulationDatasetId !== undefined) {
    missionModelId = (
      await graphqlClient.request<{
        simulation_dataset_by_pk: { simulation: { plan: { model_id: number } } };
      }>(
        gql`
          query mission_model($simulationDatasetId: Int!) {
            simulation_dataset_by_pk(id: $simulationDatasetId) {
              simulation {
                plan {
                  model_id
                }
              }
            }
          }
        `,
        { simulationDatasetId },
      )
    ).simulation_dataset_by_pk.simulation.plan.model_id;
  }

  if (missionModelId === null || missionModelId === undefined) {
    throw new Error(
      `Could not determine the mission model based on the provided ${planId ? 'planId' : 'simulationDatasetId'}`,
    );
  }

  return missionModelId;
}

export async function getLatestMissionModel(graphqlClient: GraphQLClient): Promise<{
  mission_model_aggregate: {
    aggregate: {
      max: {
        id: number;
      };
    };
  };
}> {
  return graphqlClient.request<{
    mission_model_aggregate: {
      aggregate: {
        max: {
          id: number;
        };
      };
    };
  }>(
    gql`
      query GetLatestMissionModel {
        mission_model_aggregate(order_by: { uploaded_file: { modified_date: asc } }) {
          aggregate {
            max {
              id
            }
          }
        }
      }
    `,
  );
}

export async function getLatestParcel(graphqlClient: GraphQLClient): Promise<{
  parcel_aggregate: {
    aggregate: {
      max: {
        id: number;
      };
    };
  };
}> {
  return graphqlClient.request<{
    parcel_aggregate: {
      aggregate: {
        max: {
          id: number;
        };
      };
    };
  }>(
    gql`
      query GetLatestParcel {
        parcel_aggregate(order_by: { created_at: asc }) {
          aggregate {
            max {
              id
            }
          }
        }
      }
    `,
  );
}

export async function getExpansionRule(
  graphqlClient: GraphQLClient,
  missionModelId: number,
  parcelId: number,
): Promise<{
  expansion_rule: {
    id: number;
    activity_type: string;
    expansion_logic: string;
  }[];
}> {
  return graphqlClient.request<{
    expansion_rule: {
      id: number;
      activity_type: string;
      expansion_logic: string;
    }[];
  }>(
    gql`
        query GetExpansonLogic {
          expansion_rule(where: {parcel_id: {_eq: ${parcelId} }, authoring_mission_model_id: {_eq: ${missionModelId} }}) {
            id
            activity_type
            expansion_logic
          }
        }
      `,
  );
}
