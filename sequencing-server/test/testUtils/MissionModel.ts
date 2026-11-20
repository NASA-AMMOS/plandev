import fetch, {fileFrom, FormData} from 'node-fetch';
import {gql, GraphQLClient, type RequestOptions} from 'graphql-request';
import {randomUUID} from 'crypto';
import {waitMs} from './testUtils';
import {loginTestUser} from './login.js';
import fs from 'node:fs/promises';

export async function uploadMissionModel(graphqlClient: GraphQLClient): Promise<number> {
  /*
   * Insert a mission model
   */
  const banananationBuildDir = new URL('../../../examples/banananation/build/libs', import.meta.url);
  const banananationListings = await fs.readdir(banananationBuildDir.pathname);

  const banananationStats = await Promise.all(
    banananationListings.map(async listing => {
      const path = banananationBuildDir.pathname + '/' + listing;
      return {
        path,
        stats: await fs.stat(path),
      };
    }),
  );
  const latestBuild = banananationStats.sort((a, b) => a.stats.mtime.getTime() - b.stats.mtime.getTime()).reverse()[0];

  if (latestBuild === undefined) {
    throw new Error('No banananation build found');
  }

  const formData = new FormData();
  const file = await fileFrom(latestBuild.path);
  formData.set('file', file, 'banananation-latest.jar');

  // Get an authorization token
  const authHeader = `Bearer ${await loginTestUser()}`;

  // Upload File
  const uploadRes = await fetch(`${process.env['MERLIN_GATEWAY_URL']}/file`, {
    method: 'POST',
    body: formData,
    headers: { 'Authorization': authHeader },
  });
  if (!uploadRes.ok) {
    throw new Error(`Failed to upload mission model: ${uploadRes.statusText}`);
  }

  const missionModelFileId = ((await uploadRes.json()) as { id: number }).id;

  const res = await graphqlClient.request<{ insert_mission_model_one: { id: number } }>(
    gql`
      mutation InsertTestMissionModel($model: mission_model_insert_input!) {
        insert_mission_model_one(object: $model) {
          id
        }
      }
    `,
    {
      model: {
        mission: 'banananation',
        name: 'banananation' + randomUUID(),
        version: '0.0.0',
        definition_file_id: missionModelFileId,
      },
    },
  );
  const modelId = res.insert_mission_model_one.id;
  try {
    // wait for activity types to be done processing before resolving, so model will be ready
    await waitForActivityTypes(graphqlClient, modelId);
  } catch (err) {
    // remove failed model upload pre-emptively - caller won't be able to, since we're throwing instead of returning ID
    await removeMissionModel(graphqlClient, modelId, graphqlTimeout(2000)).catch(cleanupError => {
      console.warn(`Could not remove test model ${modelId}:`, cleanupError);
    });
    throw err;
  }
  return modelId;
}

type ActivityRefresh = { pending: boolean; success: boolean; status: number | null; error_message: string | null };

// todo: update to node 24 types and improve typing here
function graphqlTimeout(ms: number): NonNullable<RequestOptions['signal']> {
  return AbortSignal.timeout(ms) as unknown as NonNullable<RequestOptions['signal']>;
}

async function waitForActivityTypes(graphqlClient: GraphQLClient, modelId: number): Promise<void> {
  const signal = graphqlTimeout(30000);
  let lastState = 'no response from Hasura';
  try {
    while (!signal.aborted) {
      const result = await graphqlClient.request<{ refresh_activity_type_logs: ActivityRefresh[] }>({
        document: gql`
          query TestActivityTypeRefresh($modelId: Int!) {
            refresh_activity_type_logs(where: { model_id: { _eq: $modelId } }, order_by: { created_at: desc }, limit: 1) {
              pending
              success
              status
              error_message
            }
          }
        `,
        variables: { modelId },
        signal,
      });
      const refresh = result.refresh_activity_type_logs[0];
      if (refresh && !refresh.pending) {
        if (refresh.success) return;
        const message = refresh.error_message ?? 'no error message';
        throw new Error(`Activity type refresh failed for model ${modelId} (status ${refresh.status}): ${message}`);
      }
      lastState = refresh ? 'refresh pending' : 'no refresh event';
      await waitMs(250);
    }
  } catch (err) {
    if (!signal.aborted) throw err;
  }
  throw new Error(`Timed out waiting for activity types on model ${modelId}: ${lastState}`);
}

export async function removeMissionModel(
  graphqlClient: GraphQLClient,
  missionModelId: number,
  signal?: RequestOptions['signal'],
): Promise<void> {
  await graphqlClient.request({
    document: gql`
      mutation DeleteMissionModel($missionModelId: Int!) {
        deleteModel: delete_mission_model_by_pk(id: $missionModelId) {
          id
        }
      }
    `,
    variables: { missionModelId },
    signal,
  });
}
