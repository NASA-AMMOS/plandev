import fetch, { FormData, fileFrom } from 'node-fetch';
import { gql, GraphQLClient } from 'graphql-request';
import { randomUUID } from 'crypto';
import { waitMs } from './testUtils';
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
  const authHeader = `Bearer ${await login()}`;

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

  const res = await graphqlClient.request(
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
        jar_id: missionModelFileId,
      },
    },
  );
  await waitMs(3000);
  return (res.insert_mission_model_one as { id: number } as { id: number }).id;
}

async function login() {
  const response = await fetch(`${process.env['MERLIN_GATEWAY_URL']}/auth/login`, {
    method: 'POST',
    body: `{"username": "AerieE2ESequencingTests", "password": "password"}`,
    headers: { 'Content-Type': 'application/json' },
  });
  if (!response.ok) {
    throw new Error(`Failed to login: ${response.statusText}`);
  }
  return (await response.json() as {token: string}).token;
}

export async function removeMissionModel(graphqlClient: GraphQLClient, missionModelId: number): Promise<void> {
  /*
   * Remove a mission model
   */
  await graphqlClient.request(
    gql`
      mutation DeleteMissionModel($missionModelId: Int!) {
        deleteModel: delete_mission_model_by_pk(id: $missionModelId) {
          id
        }
      }
    `,
    {
      missionModelId,
    },
  );
}
