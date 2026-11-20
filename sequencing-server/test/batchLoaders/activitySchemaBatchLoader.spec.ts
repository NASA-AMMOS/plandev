import type { GraphQLClient } from 'graphql-request';
import { activitySchemaBatchLoader } from '../../src/lib/batchLoaders/activitySchemaBatchLoader.js';
import { removeMissionModel, uploadMissionModel } from '../testUtils/MissionModel.js';
import { getGraphQLClient } from '../testUtils/testUtils.js';

let graphqlClient: GraphQLClient;
let missionModelId: number;

beforeAll(async () => {
  graphqlClient = await getGraphQLClient();
  missionModelId = await uploadMissionModel(graphqlClient);
}, 45000);

afterAll(async () => {
  if (missionModelId !== undefined) await removeMissionModel(graphqlClient, missionModelId);
});

it('should load activity schemas', async () => {
  const activitySchemas = await activitySchemaBatchLoader({ graphqlClient })([
    { activityTypeName: 'ParameterTest', missionModelId },
  ]);

  const firstSchema = activitySchemas[0];
  if (firstSchema instanceof Error) {
    throw firstSchema;
  }
  expect(firstSchema?.name).toBe('ParameterTest');
});
