import { gql, GraphQLClient } from 'graphql-request';
import { TimingTypes } from '../../src/lib/codegen/CommandEDSLPreface.js';
import {
  convertActivityDirectiveIdToSimulatedActivityId,
  insertActivityDirective,
  removeActivityDirective,
} from './testUtils/ActivityDirective.js';
import { insertDictionary, removeDictionary } from '../testUtils/Dictionary';
import {
  expand,
  getExpandedSequence,
  getExpansionSet,
  insertExpansion,
  insertExpansionSet,
  removeExpansion,
  removeExpansionRun,
  removeExpansionSet,
} from './testUtils/Expansion.js';
import { removeMissionModel, uploadMissionModel } from '../testUtils/MissionModel.js';
import { createPlan, removePlan } from '../testUtils/Plan.js';
import { executeSimulation, removeSimulationArtifacts, updateSimulationBounds } from '../testUtils/Simulation.js';
import { getGraphQLClient, waitMs } from '../testUtils/testUtils';
import { insertSequence, linkActivityInstance } from '../testUtils/Sequence.js';
import { insertParcel, removeParcel } from '../testUtils/Parcel';
import { DictionaryType } from '../../src/types/types';

let planId: number;
let graphqlClient: GraphQLClient;
let missionModelId: number;

beforeAll(async () => {
  graphqlClient = await getGraphQLClient();
});

beforeEach(async () => {
  missionModelId = await uploadMissionModel(graphqlClient);
  planId = await createPlan(graphqlClient, missionModelId);
  await updateSimulationBounds(graphqlClient, {
    plan_id: planId,
    simulation_start_time: '2020-001T00:00:00Z',
    simulation_end_time: '2020-002T00:00:00Z',
  });
});

afterEach(async () => {
  await removePlan(graphqlClient, planId);
  await removeMissionModel(graphqlClient, missionModelId);
});

describe('template expansion', () => {
  beforeEach(async () => {
    // TODO insert template(s)
  });
  afterEach(async () => {
    // TODO remove template(s)
  });

  it ('should expand templates', async () => {
    // TODO add some activities to the plan
    // TODO simulate
    // TODO run template expansion
    // TODO create sequence
    // TODO link activity instances
    // TODO retrieve merged SeqN
  })
})
