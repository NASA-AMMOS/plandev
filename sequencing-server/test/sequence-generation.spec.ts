import type { GraphQLClient } from 'graphql-request';
import { TimingTypes } from '../src/lib/codegen/CommandEDSLPreface.js';
import { DictionaryType, FallibleStatus } from '../src/types/types';
import {
  convertActivityDirectiveIdToSimulatedActivityId,
  insertActivityDirective,
  removeActivityDirective,
} from './testUtils/ActivityDirective.js';
import { insertDictionary, removeDictionary } from './testUtils/Dictionary';
import {
  expandLegacy,
  insertExpansion,
  insertExpansionSet,
  removeExpansion,
  removeExpansionRun,
  removeExpansionSet,
} from './testUtils/Expansion.js';
import { removeMissionModel, uploadMissionModel } from './testUtils/MissionModel.js';
import { createPlan, removePlan } from './testUtils/Plan.js';
import {
  getSequenceSeqJson,
  getSequenceSeqJsonBulk,
  insertSequence,
  linkActivityInstance,
  removeSequence,
} from './testUtils/Sequence.js';
import { executeSimulation, removeSimulationArtifacts, updateSimulationBounds } from './testUtils/Simulation.js';
import { getGraphQLClient } from './testUtils/testUtils.js';
import { insertParcel, removeParcel } from './testUtils/Parcel';

let planId: number;
let graphqlClient: GraphQLClient;
let missionModelId: number;
let commandDictionaryId: number;
let channelDictionaryId: number;
let parameterDictionaryId: number;
let parcelId: number;

beforeAll(async () => {
  graphqlClient = await getGraphQLClient();
  commandDictionaryId = (await insertDictionary(graphqlClient, DictionaryType.COMMAND)).command.id;
  channelDictionaryId = (await insertDictionary(graphqlClient, DictionaryType.CHANNEL)).channel.id;
  parameterDictionaryId = (await insertDictionary(graphqlClient, DictionaryType.PARAMETER)).parameter.id;
  parcelId = (
    await insertParcel(
      graphqlClient,
      commandDictionaryId,
      channelDictionaryId,
      parameterDictionaryId,
      'sequenceGenerationTestParcel',
    )
  ).parcelId;
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

afterAll(async () => {
  await removeParcel(graphqlClient, parcelId);
  await removeDictionary(graphqlClient, commandDictionaryId, DictionaryType.COMMAND);
  await removeDictionary(graphqlClient, channelDictionaryId, DictionaryType.CHANNEL);
  await removeDictionary(graphqlClient, parameterDictionaryId, DictionaryType.PARAMETER);
});

afterEach(async () => {
  await removePlan(graphqlClient, planId);
  await removeMissionModel(graphqlClient, missionModelId);
});

describe('sequence generation', () => {
  let expansionId1: number;
  let expansionId2: number;
  let expansionId3: number;
  let expansionId4: number;
  let expansionId5: number;

  beforeEach(async () => {
    expansionId1 = await insertExpansion(
      graphqlClient,
      'GrowBanana',
      `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        C.PREHEAT_OVEN({ temperature: 70 }),
        C.PREPARE_LOAF({ gluten_free: "FALSE", tb_sugar: 50 }),
        C.BAKE_BREAD,
      ];
    }
    `,
      parcelId,
    );
    expansionId2 = await insertExpansion(
      graphqlClient,
      'PeelBanana',
      `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        C.PREHEAT_OVEN(70),
        C.BAKE_BREAD,
        C.PREPARE_LOAF({ tb_sugar: 50, gluten_free: "FALSE" }),
      ];
    }
    `,
      parcelId,
    );
    expansionId3 = await insertExpansion(
      graphqlClient,
      'ThrowBanana',
      `
    export default function TimeDynamicCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        A\`2020-060T03:45:19\`.ADD_WATER,
        A(Temporal.Instant.from("2025-12-24T12:01:59Z")).PREHEAT_OVEN({ temperature: 360 }),
        R\`00:15:30\`.PREHEAT_OVEN({ temperature: 425 }),
        R(Temporal.Duration.from({ hours: 1, minutes: 15, seconds: 30 })).EAT_BANANA,
        E(Temporal.Duration.from({ days: -1, hours: -12, minutes: -16, seconds: -54 })).PREPARE_LOAF( 50,  "FALSE"),
        E\`04:56:54\`.EAT_BANANA,
        C.PACKAGE_BANANA({
          bundle: [
            {
              bundle_name: "Chiquita",
              number_of_bananas: 43
            },
            {
              number_of_bananas: 12,
              bundle_name: "Dole"
            }
          ],
          lot_number:  1093
        }),
        C.PACKAGE_BANANA({
          lot_number: 1093,
          bundle: [
            {
              bundle_name: "Chiquita",
              number_of_bananas: 43
            },
            {
              bundle_name: "Blue",
              number_of_bananas: 12
            }
          ]
        }),
        C.PACKAGE_BANANA(1034,[['companyA',100_000],['companyB',10_100]]),
      ];
    }
    `,
      parcelId,
    );

    expansionId4 = await insertExpansion(
      graphqlClient,
      'BakeBananaBread',
      `
      export default function MyExpansion(props: {
        activityInstance: ActivityType
      }): ExpansionReturn {
        const { activityInstance } = props;
        return [
          A("2022-203T00:00:00").LOAD("BACKGROUND-A").ARGUMENTS(props.activityInstance.attributes.arguments.temperature),
          A("2022-204T00:00:00").ACTIVATE("BACKGROUND-B"),
          R("00:00:90").ADD_WATER
          ];
      }
    `,
      parcelId,
    );

    expansionId5 = await insertExpansion(
      graphqlClient,
      'BananaNap',
      `
      export default function MyExpansion(props: {
        activityInstance: ActivityType,
        channelDictionary: ChannelDictionary | null
        parameterDictionaries : ParameterDictionary[]
      }): ExpansionReturn {
        const { activityInstance, channelDictionary, parameterDictionaries } = props;
        return [
          ...(channelDictionary ? channelDictionary.telemetries.map(t => C.ECHO('Telemetry Name: '+t.name)) : []),
          ...parameterDictionaries[0].params.map(p => C.ECHO('Parameter Name: '+p.param_name))
        ];
      }
    `,
      parcelId,
    );
  });

  afterEach(async () => {
    await removeExpansion(graphqlClient, expansionId1);
    await removeExpansion(graphqlClient, expansionId2);
    await removeExpansion(graphqlClient, expansionId3);
    await removeExpansion(graphqlClient, expansionId4);
  });

  it('should return sequence seqjson', async () => {
    /** Begin Setup */
    // Create Expansion Set
    const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [
      expansionId1,
      expansionId2,
      expansionId3,
      expansionId4,
    ]);

    // Create Activity Directives
    const [activityId1, activityId2, activityId3, activityId4] = await Promise.all([
      insertActivityDirective(graphqlClient, planId, 'GrowBanana'),
      insertActivityDirective(graphqlClient, planId, 'PeelBanana', '30 minutes'),
      insertActivityDirective(graphqlClient, planId, 'ThrowBanana', '60 minutes'),
      insertActivityDirective(graphqlClient, planId, 'BakeBananaBread', '90 minutes', {
        tbSugar: 1,
        glutenFree: false,
      }),
    ]);

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
    // Expand Plan to Sequence Fragments
    const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);
    // Create Sequence
    const sequencePk = await insertSequence(graphqlClient, {
      seqId: 'test00000',
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
    });
    // Link Activity Instances to Sequence
    await Promise.all([
      linkActivityInstance(graphqlClient, sequencePk, activityId1),
      linkActivityInstance(graphqlClient, sequencePk, activityId2),
      linkActivityInstance(graphqlClient, sequencePk, activityId3),
      linkActivityInstance(graphqlClient, sequencePk, activityId4),
    ]);

    // Get the simulated activity ids
    const [simulatedActivityId1, simulatedActivityId2, simulatedActivityId3, simulatedActivityId4] = await Promise.all([
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId1,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId2,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId3,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId4,
      ),
    ]);
    /** End Setup */

    // Retrieve seqJson
    const getSequenceSeqJsonResponse = await getSequenceSeqJson(
      graphqlClient,
      'test00000',
      simulationArtifactPk.simulationDatasetId,
    );

    if (getSequenceSeqJsonResponse.status !== FallibleStatus.SUCCESS) {
      throw getSequenceSeqJsonResponse.errors;
    }

    expect(getSequenceSeqJsonResponse.seqJson.id).toBe('test00000');
    expect(getSequenceSeqJsonResponse.seqJson.metadata).toEqual({
      planId: planId,
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      timeSorted: false,
    });
    expect(getSequenceSeqJsonResponse.seqJson.steps).toEqual([
      {
        // expansion 1
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        // expansion 2
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        // expansion 4
        type: 'command',
        stem: 'ADD_WATER',
        time: {
          tag: '2020-060T03:45:19.000',
          type: 'ABSOLUTE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '2025-358T12:01:59.000',
          type: 'ABSOLUTE',
        },
        args: [{ value: 360, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '00:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [{ value: 425, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '01:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: {
          tag: '-001T12:16:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '04:56:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Dole',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Blue',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1034 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyA',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 100000,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyB',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 10100,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        args: [
          {
            name: 'arg_0',
            type: 'number',
            value: 350,
          },
        ],
        metadata: {
          simulatedActivityId: simulatedActivityId4,
        },
        sequence: 'BACKGROUND-A',
        time: {
          tag: '2022-203T00:00:00.000',
          type: 'ABSOLUTE',
        },
        type: 'load',
      },
      {
        metadata: {
          simulatedActivityId: simulatedActivityId4,
        },
        sequence: 'BACKGROUND-B',
        time: {
          tag: '2022-204T00:00:00.000',
          type: 'ABSOLUTE',
        },
        type: 'activate',
      },
      {
        args: [],
        metadata: {
          simulatedActivityId: simulatedActivityId4,
        },
        stem: 'ADD_WATER',
        time: {
          tag: '00:01:30.000',
          type: 'COMMAND_RELATIVE',
        },
        type: 'command',
      },
    ]);

    /** Begin Cleanup */
    await removeSequence(graphqlClient, sequencePk);
    await removeExpansionRun(graphqlClient, expansionRunPk);
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    await Promise.all([
      removeActivityDirective(graphqlClient, activityId1, planId),
      removeActivityDirective(graphqlClient, activityId2, planId),
      removeActivityDirective(graphqlClient, activityId3, planId),
      removeActivityDirective(graphqlClient, activityId4, planId),
    ]);
    await removeExpansionSet(graphqlClient, expansionSetId);
    /** End Cleanup */
  }, 30000);

  it('should return sequence seqjson in bulk', async () => {
    /** Begin Setup */
    // Create Expansion Set
    const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [
      expansionId1,
      expansionId2,
      expansionId3,
    ]);

    // Create Activity Directives
    const [activityId1, activityId2, activityId3, activityId4, activityId5, activityId6] = await Promise.all([
      insertActivityDirective(graphqlClient, planId, 'GrowBanana'),
      insertActivityDirective(graphqlClient, planId, 'PeelBanana', '30 minutes'),
      insertActivityDirective(graphqlClient, planId, 'ThrowBanana', '60 minutes'),
      insertActivityDirective(graphqlClient, planId, 'GrowBanana', '90 minutes'),
      insertActivityDirective(graphqlClient, planId, 'PeelBanana', '120 minutes'),
      insertActivityDirective(graphqlClient, planId, 'ThrowBanana', '150 minutes'),
    ]);

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
    // Expand Plan to Sequence Fragments
    const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);
    // Create Sequence
    const [sequencePk1, sequencePk2] = await Promise.all([
      insertSequence(graphqlClient, {
        seqId: 'test00000',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      }),
      insertSequence(graphqlClient, {
        seqId: 'test00001',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      }),
    ]);
    // Link Activity Instances to Sequence
    await Promise.all([
      linkActivityInstance(graphqlClient, sequencePk1, activityId1),
      linkActivityInstance(graphqlClient, sequencePk1, activityId2),
      linkActivityInstance(graphqlClient, sequencePk1, activityId3),
      linkActivityInstance(graphqlClient, sequencePk2, activityId4),
      linkActivityInstance(graphqlClient, sequencePk2, activityId5),
      linkActivityInstance(graphqlClient, sequencePk2, activityId6),
    ]);

    // Get the simulated activity ids
    const [
      simulatedActivityId1,
      simulatedActivityId2,
      simulatedActivityId3,
      simulatedActivityId4,
      simulatedActivityId5,
      simulatedActivityId6,
    ] = await Promise.all([
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId1,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId2,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId3,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId4,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId5,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId6,
      ),
    ]);

    /** End Setup */

    // Retrieve seqJson
    const getSequenceSeqJsonBulkResponse = await getSequenceSeqJsonBulk(graphqlClient, [
      {
        seqId: 'test00000',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      },
      {
        seqId: 'test00001',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      },
    ]);

    const firstSequence = getSequenceSeqJsonBulkResponse[0]!;

    if (firstSequence.status === FallibleStatus.FAILURE) {
      throw firstSequence.errors;
    }

    expect(firstSequence.seqJson.id).toBe('test00000');
    expect(firstSequence.seqJson.metadata).toEqual({
      planId: planId,
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      timeSorted: false,
    });
    expect(firstSequence.seqJson.steps).toEqual([
      {
        // expansion 1
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        // expansion 2
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        // expansion 4
        type: 'command',
        stem: 'ADD_WATER',
        time: {
          tag: '2020-060T03:45:19.000',
          type: 'ABSOLUTE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '2025-358T12:01:59.000',
          type: 'ABSOLUTE',
        },
        args: [{ value: 360, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '00:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [{ value: 425, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '01:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: {
          tag: '-001T12:16:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '04:56:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Dole',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Blue',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1034 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyA',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 100000,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyB',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 10100,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
    ]);

    const secondSequence = getSequenceSeqJsonBulkResponse[1]!;

    if (secondSequence.status === FallibleStatus.FAILURE) {
      throw secondSequence.errors;
    }

    expect(secondSequence.seqJson.id).toBe('test00001');
    expect(secondSequence.seqJson.metadata).toEqual({
      planId: planId,
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      timeSorted: false,
    });
    expect(secondSequence.seqJson.steps).toEqual([
      {
        // expansion 1
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId4 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId4 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId4 },
      },
      {
        // expansion 2
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId5 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId5 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId5 },
      },
      {
        // expansion 4
        type: 'command',
        stem: 'ADD_WATER',
        time: {
          tag: '2020-060T03:45:19.000',
          type: 'ABSOLUTE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '2025-358T12:01:59.000',
          type: 'ABSOLUTE',
        },
        args: [{ value: 360, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '00:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [{ value: 425, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '01:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: {
          tag: '-001T12:16:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '04:56:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Dole',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Blue',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1034 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyA',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 100000,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyB',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 10100,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
    ]);

    /** Begin Cleanup */
    await Promise.all([removeSequence(graphqlClient, sequencePk1), removeSequence(graphqlClient, sequencePk2)]);
    await removeExpansionRun(graphqlClient, expansionRunPk);
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    await Promise.all([
      removeActivityDirective(graphqlClient, activityId1, planId),
      removeActivityDirective(graphqlClient, activityId2, planId),
      removeActivityDirective(graphqlClient, activityId3, planId),
      removeActivityDirective(graphqlClient, activityId4, planId),
      removeActivityDirective(graphqlClient, activityId5, planId),
      removeActivityDirective(graphqlClient, activityId6, planId),
    ]);
    await removeExpansionSet(graphqlClient, expansionSetId);
    /** End Cleanup */
  }, 30000);

  it('should work for throwing expansions', async () => {
    /** Begin Setup */
    // Add throwing expansion
    const localExpansionId = await insertExpansion(
      graphqlClient,
      'BiteBanana',
      `
      export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
        throw new Error('Unimplemented');
      }
      `,
      parcelId,
    );

    // Create Expansion Set
    const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [
      expansionId1,
      expansionId2,
      expansionId3,
      localExpansionId,
    ]);

    // Create Activity Directives
    const [activityId1, activityId2, activityId3, activityId4] = await Promise.all([
      insertActivityDirective(graphqlClient, planId, 'GrowBanana'),
      insertActivityDirective(graphqlClient, planId, 'PeelBanana', '30 minutes'),
      insertActivityDirective(graphqlClient, planId, 'ThrowBanana', '60 minutes'),
      insertActivityDirective(graphqlClient, planId, 'BiteBanana', '90 minutes'),
    ]);

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
    // Expand Plan to Sequence Fragments
    const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);
    // Create Sequence
    const sequencePk = await insertSequence(graphqlClient, {
      seqId: 'test00000',
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
    });
    // Link Activity Instances to Sequence
    await Promise.all([
      linkActivityInstance(graphqlClient, sequencePk, activityId1),
      linkActivityInstance(graphqlClient, sequencePk, activityId2),
      linkActivityInstance(graphqlClient, sequencePk, activityId3),
      linkActivityInstance(graphqlClient, sequencePk, activityId4),
    ]);

    // Get the simulated activity ids
    const [simulatedActivityId1, simulatedActivityId2, simulatedActivityId3, simulatedActivityId4] = await Promise.all([
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId1,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId2,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId3,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId4,
      ),
    ]);

    /** End Setup */

    // Retrieve seqJson
    const getSequenceSeqJsonResponse = await getSequenceSeqJson(
      graphqlClient,
      'test00000',
      simulationArtifactPk.simulationDatasetId,
    );

    expect(getSequenceSeqJsonResponse.errors).toIncludeAllMembers([
      { message: 'Error: Unimplemented', stack: 'at SingleCommandExpansion(3:14)' },
    ]);

    expect(getSequenceSeqJsonResponse.seqJson?.id).toBe('test00000');
    expect(getSequenceSeqJsonResponse.seqJson?.metadata).toEqual({
      planId: planId,
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      timeSorted: false,
    });
    expect(getSequenceSeqJsonResponse.seqJson?.steps).toEqual([
      {
        // expansion 1
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        // expansion 2
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        // expansion 4
        type: 'command',
        stem: 'ADD_WATER',
        time: {
          tag: '2020-060T03:45:19.000',
          type: 'ABSOLUTE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '2025-358T12:01:59.000',
          type: 'ABSOLUTE',
        },
        args: [{ value: 360, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '00:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [{ value: 425, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '01:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: {
          tag: '-001T12:16:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '04:56:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Dole',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Blue',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1034 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyA',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 100000,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyB',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 10100,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: '$$ERROR$$',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ name: 'message', type: 'string', value: 'Error: Unimplemented' }],
        metadata: { simulatedActivityId: simulatedActivityId4 },
      },
    ]);

    /** Begin Cleanup */
    await removeSequence(graphqlClient, sequencePk);
    await removeExpansionRun(graphqlClient, expansionRunPk);
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    await Promise.all([
      removeActivityDirective(graphqlClient, activityId1, planId),
      removeActivityDirective(graphqlClient, activityId2, planId),
      removeActivityDirective(graphqlClient, activityId3, planId),
    ]);
    await removeExpansion(graphqlClient, localExpansionId);
    await removeExpansionSet(graphqlClient, expansionSetId);
    /** End Cleanup */
  }, 30000);

  it('should work for throwing expansions in bulk', async () => {
    /** Begin Setup */
    const localExpansionId = await insertExpansion(
      graphqlClient,
      'BiteBanana',
      `
      export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
        throw new Error('Unimplemented');
      }
      `,
      parcelId,
    );
    // Create Expansion Set
    const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [
      expansionId1,
      expansionId2,
      expansionId3,
      localExpansionId,
    ]);

    // Create Activity Directives
    const [activityId1, activityId2, activityId3, activityId4, activityId5, activityId6, activityId7, activityId8] =
      await Promise.all([
        insertActivityDirective(graphqlClient, planId, 'GrowBanana'),
        insertActivityDirective(graphqlClient, planId, 'PeelBanana', '30 minutes'),
        insertActivityDirective(graphqlClient, planId, 'ThrowBanana', '60 minutes'),
        insertActivityDirective(graphqlClient, planId, 'BiteBanana', '90 minutes'),
        insertActivityDirective(graphqlClient, planId, 'GrowBanana', '120 minutes'),
        insertActivityDirective(graphqlClient, planId, 'PeelBanana', '150 minutes'),
        insertActivityDirective(graphqlClient, planId, 'ThrowBanana', '180 minutes'),
        insertActivityDirective(graphqlClient, planId, 'BiteBanana', '210 minutes'),
      ]);

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
    // Expand Plan to Sequence Fragments
    const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);
    // Create Sequence
    const [sequencePk1, sequencePk2] = await Promise.all([
      insertSequence(graphqlClient, {
        seqId: 'test00000',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      }),
      insertSequence(graphqlClient, {
        seqId: 'test00001',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      }),
    ]);
    // Link Activity Instances to Sequence
    await Promise.all([
      linkActivityInstance(graphqlClient, sequencePk1, activityId1),
      linkActivityInstance(graphqlClient, sequencePk1, activityId2),
      linkActivityInstance(graphqlClient, sequencePk1, activityId3),
      linkActivityInstance(graphqlClient, sequencePk1, activityId4),
      linkActivityInstance(graphqlClient, sequencePk2, activityId5),
      linkActivityInstance(graphqlClient, sequencePk2, activityId6),
      linkActivityInstance(graphqlClient, sequencePk2, activityId7),
      linkActivityInstance(graphqlClient, sequencePk2, activityId8),
    ]);

    // Get the simulated activity ids
    const [
      simulatedActivityId1,
      simulatedActivityId2,
      simulatedActivityId3,
      simulatedActivityId4,
      simulatedActivityId5,
      simulatedActivityId6,
      simulatedActivityId7,
      simulatedActivityId8,
    ] = await Promise.all([
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId1,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId2,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId3,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId4,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId5,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId6,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId7,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId8,
      ),
    ]);

    /** End Setup */

    // Retrieve seqJson
    const getSequenceSeqJsonBulkResponse = await getSequenceSeqJsonBulk(graphqlClient, [
      {
        seqId: 'test00000',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      },
      {
        seqId: 'test00001',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      },
    ]);

    const firstSequence = getSequenceSeqJsonBulkResponse[0]!;

    expect(firstSequence.seqJson?.id).toBe('test00000');
    expect(firstSequence.seqJson?.metadata).toEqual({
      planId: planId,
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      timeSorted: false,
    });
    expect(firstSequence.seqJson?.steps).toEqual([
      {
        // expansion 1
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        // expansion 2
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        // expansion 4
        type: 'command',
        stem: 'ADD_WATER',
        time: {
          tag: '2020-060T03:45:19.000',
          type: 'ABSOLUTE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '2025-358T12:01:59.000',
          type: 'ABSOLUTE',
        },
        args: [{ value: 360, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '00:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [{ value: 425, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '01:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: {
          tag: '-001T12:16:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '04:56:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Dole',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Blue',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1034 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyA',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 100000,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyB',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 10100,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: '$$ERROR$$',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ name: 'message', type: 'string', value: 'Error: Unimplemented' }],
        metadata: { simulatedActivityId: simulatedActivityId4 },
      },
    ]);

    const secondSequence = getSequenceSeqJsonBulkResponse[1]!;

    expect(secondSequence.seqJson?.id).toBe('test00001');
    expect(secondSequence.seqJson?.metadata).toEqual({
      planId: planId,
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      timeSorted: false,
    });
    expect(secondSequence.seqJson?.steps).toEqual([
      {
        // expansion 1
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId5 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId5 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId5 },
      },
      {
        // expansion 2
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        // expansion 4
        type: 'command',
        stem: 'ADD_WATER',
        time: {
          tag: '2020-060T03:45:19.000',
          type: 'ABSOLUTE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '2025-358T12:01:59.000',
          type: 'ABSOLUTE',
        },
        args: [{ value: 360, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '00:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [{ value: 425, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '01:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: {
          tag: '-001T12:16:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '04:56:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Dole',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Blue',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1034 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyA',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 100000,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyB',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 10100,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: '$$ERROR$$',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ name: 'message', type: 'string', value: 'Error: Unimplemented' }],
        metadata: { simulatedActivityId: simulatedActivityId8 },
      },
    ]);

    /** Begin Cleanup */
    await Promise.all([removeSequence(graphqlClient, sequencePk1), removeSequence(graphqlClient, sequencePk2)]);
    await removeExpansionRun(graphqlClient, expansionRunPk);
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    await Promise.all([
      removeActivityDirective(graphqlClient, activityId1, planId),
      removeActivityDirective(graphqlClient, activityId2, planId),
      removeActivityDirective(graphqlClient, activityId3, planId),
      removeActivityDirective(graphqlClient, activityId4, planId),
      removeActivityDirective(graphqlClient, activityId5, planId),
      removeActivityDirective(graphqlClient, activityId6, planId),
      removeActivityDirective(graphqlClient, activityId7, planId),
      removeActivityDirective(graphqlClient, activityId8, planId),
    ]);
    await removeExpansion(graphqlClient, localExpansionId);
    await removeExpansionSet(graphqlClient, expansionSetId);
    /** End Cleanup */
  }, 30000);

  it('should work for non-existent expansions', async () => {
    /** Begin Setup */
    // Create Expansion Set
    const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [
      expansionId1,
      expansionId2,
      expansionId3,
    ]);

    // Create Activity Directives
    const [activityId1, activityId2, activityId3, activityId4] = await Promise.all([
      insertActivityDirective(graphqlClient, planId, 'GrowBanana'),
      insertActivityDirective(graphqlClient, planId, 'PeelBanana', '30 minutes'),
      insertActivityDirective(graphqlClient, planId, 'ThrowBanana', '60 minutes'),
      insertActivityDirective(graphqlClient, planId, 'BiteBanana', '90 minutes'), // non-existent expansion
    ]);

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
    // Expand Plan to Sequence Fragments
    const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);
    // Create Sequence
    const sequencePk = await insertSequence(graphqlClient, {
      seqId: 'test00000',
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
    });
    // Link Activity Instances to Sequence
    await Promise.all([
      linkActivityInstance(graphqlClient, sequencePk, activityId1),
      linkActivityInstance(graphqlClient, sequencePk, activityId2),
      linkActivityInstance(graphqlClient, sequencePk, activityId3),
      linkActivityInstance(graphqlClient, sequencePk, activityId4),
    ]);

    // Get the simulated activity ids
    const [
      simulatedActivityId1,
      simulatedActivityId2,
      simulatedActivityId3,
      _simulatedActivityId4, // No expansion, so no check required on this one
    ] = await Promise.all([
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId1,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId2,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId3,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId4,
      ),
    ]);

    /** End Setup */

    // Retrieve seqJson
    const getSequenceSeqJsonResponse = await getSequenceSeqJson(
      graphqlClient,
      'test00000',
      simulationArtifactPk.simulationDatasetId,
    );

    if (getSequenceSeqJsonResponse.status !== FallibleStatus.SUCCESS) {
      throw getSequenceSeqJsonResponse.errors;
    }

    expect(getSequenceSeqJsonResponse.seqJson.id).toBe('test00000');
    expect(getSequenceSeqJsonResponse.seqJson.metadata).toEqual({
      planId: planId,
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      timeSorted: false,
    });
    expect(getSequenceSeqJsonResponse.seqJson.steps).toEqual([
      {
        // expansion 1
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        // expansion 2
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        // expansion 4
        type: 'command',
        stem: 'ADD_WATER',
        time: {
          tag: '2020-060T03:45:19.000',
          type: 'ABSOLUTE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '2025-358T12:01:59.000',
          type: 'ABSOLUTE',
        },
        args: [{ value: 360, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '00:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [{ value: 425, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '01:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: {
          tag: '-001T12:16:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '04:56:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Dole',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Blue',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1034 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyA',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 100000,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyB',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 10100,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
    ]);

    /** Begin Cleanup */
    await removeSequence(graphqlClient, sequencePk);
    await removeExpansionRun(graphqlClient, expansionRunPk);
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    await Promise.all([
      removeActivityDirective(graphqlClient, activityId1, planId),
      removeActivityDirective(graphqlClient, activityId2, planId),
      removeActivityDirective(graphqlClient, activityId3, planId),
    ]);
    await removeExpansionSet(graphqlClient, expansionSetId);
    /** End Cleanup */
  }, 30000);

  it('should work for non-existent expansions in bulk', async () => {
    /** Begin Setup */
    // Create Expansion Set
    const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [
      expansionId1,
      expansionId2,
      expansionId3,
    ]);

    // Create Activity Directives
    const [activityId1, activityId2, activityId3, activityId4, activityId5, activityId6, activityId7, activityId8] =
      await Promise.all([
        insertActivityDirective(graphqlClient, planId, 'GrowBanana'),
        insertActivityDirective(graphqlClient, planId, 'PeelBanana', '30 minutes'),
        insertActivityDirective(graphqlClient, planId, 'ThrowBanana', '60 minutes'),
        insertActivityDirective(graphqlClient, planId, 'BiteBanana', '90 minutes'), // non-existent expansion
        insertActivityDirective(graphqlClient, planId, 'GrowBanana', '120 minutes'),
        insertActivityDirective(graphqlClient, planId, 'PeelBanana', '150 minutes'),
        insertActivityDirective(graphqlClient, planId, 'ThrowBanana', '180 minutes'),
        insertActivityDirective(graphqlClient, planId, 'BiteBanana', '210 minutes'), // non-existent expansion
      ]);

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
    // Expand Plan to Sequence Fragments
    const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);
    // Create Sequence
    const [sequencePk1, sequencePk2] = await Promise.all([
      insertSequence(graphqlClient, {
        seqId: 'test00000',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      }),
      insertSequence(graphqlClient, {
        seqId: 'test00001',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      }),
    ]);
    // Link Activity Instances to Sequence
    await Promise.all([
      linkActivityInstance(graphqlClient, sequencePk1, activityId1),
      linkActivityInstance(graphqlClient, sequencePk1, activityId2),
      linkActivityInstance(graphqlClient, sequencePk1, activityId3),
      linkActivityInstance(graphqlClient, sequencePk1, activityId4),
      linkActivityInstance(graphqlClient, sequencePk2, activityId5),
      linkActivityInstance(graphqlClient, sequencePk2, activityId6),
      linkActivityInstance(graphqlClient, sequencePk2, activityId7),
      linkActivityInstance(graphqlClient, sequencePk2, activityId8),
    ]);

    // Get the simulated activity ids
    const [
      simulatedActivityId1,
      simulatedActivityId2,
      simulatedActivityId3,
      _simulatedActivityId4, // No expansion, so no check required on this one
      simulatedActivityId5,
      simulatedActivityId6,
      simulatedActivityId7,
      _simulatedActivityId8, // No expansion, so no check required on this one
    ] = await Promise.all([
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId1,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId2,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId3,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId4,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId5,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId6,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId7,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId8,
      ),
    ]);

    /** End Setup */

    // Retrieve seqJson
    const getSequenceSeqJsonResponse = await getSequenceSeqJsonBulk(graphqlClient, [
      { seqId: 'test00000', simulationDatasetId: simulationArtifactPk.simulationDatasetId },
      { seqId: 'test00001', simulationDatasetId: simulationArtifactPk.simulationDatasetId },
    ]);

    const firstSequence = getSequenceSeqJsonResponse[0]!;

    if (firstSequence.status !== FallibleStatus.SUCCESS) {
      throw firstSequence.errors;
    }

    expect(firstSequence.seqJson.id).toBe('test00000');
    expect(firstSequence.seqJson.metadata).toEqual({
      planId: planId,
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      timeSorted: false,
    });
    expect(firstSequence.seqJson.steps).toEqual([
      {
        // expansion 1
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId1 },
      },
      {
        // expansion 2
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId2 },
      },
      {
        // expansion 4
        type: 'command',
        stem: 'ADD_WATER',
        time: {
          tag: '2020-060T03:45:19.000',
          type: 'ABSOLUTE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '2025-358T12:01:59.000',
          type: 'ABSOLUTE',
        },
        args: [{ value: 360, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '00:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [{ value: 425, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '01:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: {
          tag: '-001T12:16:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '04:56:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Dole',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Blue',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1034 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyA',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 100000,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyB',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 10100,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId3 },
      },
    ]);

    const secondSequence = getSequenceSeqJsonResponse[1]!;

    if (secondSequence.status !== FallibleStatus.SUCCESS) {
      throw secondSequence.errors;
    }

    expect(secondSequence.seqJson.id).toBe('test00001');
    expect(secondSequence.seqJson.metadata).toEqual({
      planId: planId,
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      timeSorted: false,
    });
    expect(secondSequence.seqJson.steps).toEqual([
      {
        // expansion 1
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId5 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId5 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId5 },
      },
      {
        // expansion 2
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        type: 'command',
        stem: 'BAKE_BREAD',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId6 },
      },
      {
        // expansion 4
        type: 'command',
        stem: 'ADD_WATER',
        time: {
          tag: '2020-060T03:45:19.000',
          type: 'ABSOLUTE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '2025-358T12:01:59.000',
          type: 'ABSOLUTE',
        },
        args: [{ value: 360, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'PREHEAT_OVEN',
        time: {
          tag: '00:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [{ value: 425, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '01:15:30.000',
          type: 'COMMAND_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'PREPARE_LOAF',
        time: {
          tag: '-001T12:16:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [
          { value: 50, name: 'tb_sugar', type: 'number' },
          { value: 'FALSE', name: 'gluten_free', type: 'string' },
        ],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'EAT_BANANA',
        time: {
          tag: '04:56:54.000',
          type: 'EPOCH_RELATIVE',
        },
        args: [],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Dole',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1093 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Chiquita',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 43,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'Blue',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 12,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
      {
        type: 'command',
        stem: 'PACKAGE_BANANA',
        time: { type: TimingTypes.COMMAND_COMPLETE },
        args: [
          { name: 'lot_number', type: 'number', value: 1034 },
          {
            name: 'bundle',
            type: 'repeat',
            value: [
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyA',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 100000,
                },
              ],
              [
                {
                  name: 'bundle_name',
                  type: 'string',
                  value: 'companyB',
                },
                {
                  name: 'number_of_bananas',
                  type: 'number',
                  value: 10100,
                },
              ],
            ],
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId7 },
      },
    ]);

    /** Begin Cleanup */
    await Promise.all([removeSequence(graphqlClient, sequencePk1), removeSequence(graphqlClient, sequencePk2)]);
    await removeExpansionRun(graphqlClient, expansionRunPk);
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    await Promise.all([
      removeActivityDirective(graphqlClient, activityId1, planId),
      removeActivityDirective(graphqlClient, activityId2, planId),
      removeActivityDirective(graphqlClient, activityId3, planId),
      removeActivityDirective(graphqlClient, activityId4, planId),
      removeActivityDirective(graphqlClient, activityId5, planId),
      removeActivityDirective(graphqlClient, activityId6, planId),
      removeActivityDirective(graphqlClient, activityId7, planId),
      removeActivityDirective(graphqlClient, activityId8, planId),
    ]);
    await removeExpansionSet(graphqlClient, expansionSetId);
    /** End Cleanup */
  }, 30000);

  describe('step sorting', () => {
    it('should sort expansions correctly with relative and absolute times', async () => {
      /** Begin Setup */
      const expansionId = await insertExpansion(
        graphqlClient,
        'GrowBanana',
        `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        A\`2023-091T08:19:00.000\`.ADD_WATER,
        R\`04:00:00.000\`.PICK_BANANA,
        A\`2023-091T04:20:00.000\`.GROW_BANANA({ quantity: 10, durationSecs: 7200 })
      ];
    }
    `,
        parcelId,
      );
      // Create Expansion Set
      const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [expansionId]);

      // Create Activity Directives
      const [activityId1, activityId2] = await Promise.all([
        insertActivityDirective(graphqlClient, planId, 'GrowBanana'),
        insertActivityDirective(graphqlClient, planId, 'GrowBanana', '30 minutes'),
      ]);

      // Simulate Plan
      const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
      // Expand Plan to Sequence Fragments
      const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);
      // Create Sequence
      const sequencePk = await insertSequence(graphqlClient, {
        seqId: 'test00000',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      });
      // Link Activity Instances to Sequence
      await Promise.all([
        linkActivityInstance(graphqlClient, sequencePk, activityId1),
        linkActivityInstance(graphqlClient, sequencePk, activityId2),
      ]);

      // Get the simulated activity ids
      const [simulatedActivityId1, simulatedActivityId2] = await Promise.all([
        convertActivityDirectiveIdToSimulatedActivityId(
          graphqlClient,
          simulationArtifactPk.simulationDatasetId,
          activityId1,
        ),
        convertActivityDirectiveIdToSimulatedActivityId(
          graphqlClient,
          simulationArtifactPk.simulationDatasetId,
          activityId2,
        ),
      ]);
      /** End Setup */

      // Retrieve seqJson
      const getSequenceSeqJsonResponse = await getSequenceSeqJson(
        graphqlClient,
        'test00000',
        simulationArtifactPk.simulationDatasetId,
      );

      if (getSequenceSeqJsonResponse.status !== FallibleStatus.SUCCESS) {
        throw getSequenceSeqJsonResponse.errors;
      }

      expect(getSequenceSeqJsonResponse.seqJson.id).toBe('test00000');
      expect(getSequenceSeqJsonResponse.seqJson.metadata).toEqual({
        planId: planId,
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
        timeSorted: true,
      });

      expect(getSequenceSeqJsonResponse.seqJson.steps).toEqual([
        {
          type: 'command',
          stem: 'GROW_BANANA',
          time: { tag: '2023-091T04:20:00.000', type: TimingTypes.ABSOLUTE },
          args: [
            { value: 10, name: 'quantity', type: 'number' },
            { value: 7200, name: 'durationSecs', type: 'number' },
          ],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
        {
          type: 'command',
          stem: 'GROW_BANANA',
          time: { tag: '2023-091T04:20:00.000', type: TimingTypes.ABSOLUTE },
          args: [
            { value: 10, name: 'quantity', type: 'number' },
            { value: 7200, name: 'durationSecs', type: 'number' },
          ],
          metadata: { simulatedActivityId: simulatedActivityId2 },
        },
        {
          type: 'command',
          stem: 'ADD_WATER',
          time: { tag: '2023-091T08:19:00.000', type: TimingTypes.ABSOLUTE },
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
        {
          type: 'command',
          stem: 'ADD_WATER',
          time: { tag: '2023-091T08:19:00.000', type: TimingTypes.ABSOLUTE },
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId2 },
        },
        {
          type: 'command',
          stem: 'PICK_BANANA',
          time: { tag: '2023-091T12:19:00.000', type: TimingTypes.ABSOLUTE },
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
        {
          type: 'command',
          stem: 'PICK_BANANA',
          time: { tag: '2023-091T12:19:00.000', type: TimingTypes.ABSOLUTE },
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId2 },
        },
      ]);

      /** Begin Cleanup */
      await removeSequence(graphqlClient, sequencePk);
      await removeExpansionRun(graphqlClient, expansionRunPk);
      await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
      await Promise.all([
        removeActivityDirective(graphqlClient, activityId1, planId),
        removeActivityDirective(graphqlClient, activityId2, planId),
      ]);
      await removeExpansion(graphqlClient, expansionId);
      await removeExpansionSet(graphqlClient, expansionSetId);
      /** End Cleanup */
    }, 30000);

    it('should sort expanded commands correctly with relative and absolute times for multiple activities', async () => {
      /** Begin Setup */
      const expansionId1 = await insertExpansion(
        graphqlClient,
        'PickBanana',
        `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        A\`2023-091T08:00:00.000\`.ADD_WATER,
        R\`04:00:00.000\`.PICK_BANANA,
      ];
    }
    `,
        parcelId,
      );

      const expansionId2 = await insertExpansion(
        graphqlClient,
        'GrowBanana',
        `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        A\`2023-091T10:00:00.000\`.ADD_WATER,
        R\`04:00:00.000\`.GROW_BANANA({ quantity: 10, durationSecs: 7200 })
      ];
    }
    `,
        parcelId,
      );
      // Create Expansion Set
      const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [
        expansionId1,
        expansionId2,
      ]);

      // Create Activity Directives
      const [activityId1, activityId2] = await Promise.all([
        insertActivityDirective(graphqlClient, planId, 'PickBanana'),
        insertActivityDirective(graphqlClient, planId, 'GrowBanana', '30 minutes'),
      ]);

      // Simulate Plan
      const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
      // Expand Plan to Sequence Fragments
      const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);
      // Create Sequence
      const sequencePk = await insertSequence(graphqlClient, {
        seqId: 'test00000',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      });
      // Link Activity Instances to Sequence
      await Promise.all([
        linkActivityInstance(graphqlClient, sequencePk, activityId1),
        linkActivityInstance(graphqlClient, sequencePk, activityId2),
      ]);

      // Get the simulated activity ids
      const [simulatedActivityId1, simulatedActivityId2] = await Promise.all([
        convertActivityDirectiveIdToSimulatedActivityId(
          graphqlClient,
          simulationArtifactPk.simulationDatasetId,
          activityId1,
        ),
        convertActivityDirectiveIdToSimulatedActivityId(
          graphqlClient,
          simulationArtifactPk.simulationDatasetId,
          activityId2,
        ),
      ]);
      /** End Setup */

      // Retrieve seqJson
      const getSequenceSeqJsonResponse = await getSequenceSeqJson(
        graphqlClient,
        'test00000',
        simulationArtifactPk.simulationDatasetId,
      );

      if (getSequenceSeqJsonResponse.status !== FallibleStatus.SUCCESS) {
        throw getSequenceSeqJsonResponse.errors;
      }

      expect(getSequenceSeqJsonResponse.seqJson.id).toBe('test00000');
      expect(getSequenceSeqJsonResponse.seqJson.metadata).toEqual({
        planId: planId,
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
        timeSorted: true,
      });

      expect(getSequenceSeqJsonResponse.seqJson.steps).toEqual([
        {
          type: 'command',
          stem: 'ADD_WATER',
          time: { tag: '2023-091T08:00:00.000', type: TimingTypes.ABSOLUTE },
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
        {
          type: 'command',
          stem: 'ADD_WATER',
          time: { tag: '2023-091T10:00:00.000', type: TimingTypes.ABSOLUTE },
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId2 },
        },
        {
          type: 'command',
          stem: 'PICK_BANANA',
          time: { tag: '2023-091T12:00:00.000', type: TimingTypes.ABSOLUTE },
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
        {
          type: 'command',
          stem: 'GROW_BANANA',
          time: { tag: '2023-091T14:00:00.000', type: TimingTypes.ABSOLUTE },
          args: [
            { value: 10, name: 'quantity', type: 'number' },
            { value: 7200, name: 'durationSecs', type: 'number' },
          ],
          metadata: { simulatedActivityId: simulatedActivityId2 },
        },
      ]);

      /** Begin Cleanup */
      await removeSequence(graphqlClient, sequencePk);
      await removeExpansionRun(graphqlClient, expansionRunPk);
      await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
      await Promise.all([
        removeActivityDirective(graphqlClient, activityId1, planId),
        removeActivityDirective(graphqlClient, activityId2, planId),
      ]);
      await removeExpansionSet(graphqlClient, expansionSetId);
      await removeExpansionRun(graphqlClient, expansionRunPk);
      await removeExpansion(graphqlClient, expansionId1);
      await removeExpansion(graphqlClient, expansionId2);
      /** End Cleanup */
    }, 30000);

    it('should resolve relative time tags to absolute and sort', async () => {
      /** Begin Setup */
      const expansionId = await insertExpansion(
        graphqlClient,
        'GrowBanana',
        `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        R\`00:00:01.000\`.PICK_BANANA,
        A\`2023-091T08:19:00.000\`.ADD_WATER,
        A\`2023-091T04:20:00.000\`.GROW_BANANA({ quantity: 10, durationSecs: 7200 })
      ];
    }
    `,
        parcelId,
      );
      // Create Expansion Set
      const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [expansionId]);

      // Create Activity Directive at plan start, 2020-001T00:00:00
      const [activityId1] = await Promise.all([insertActivityDirective(graphqlClient, planId, 'GrowBanana', "0 seconds")]);

      // Simulate Plan
      const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
      // Expand Plan to Sequence Fragments
      const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);
      // Create Sequence
      const sequencePk = await insertSequence(graphqlClient, {
        seqId: 'test00000',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      });
      // Link Activity Instances to Sequence
      await Promise.all([linkActivityInstance(graphqlClient, sequencePk, activityId1)]);

      // Get the simulated activity ids
      const [simulatedActivityId1] = await Promise.all([
        convertActivityDirectiveIdToSimulatedActivityId(
          graphqlClient,
          simulationArtifactPk.simulationDatasetId,
          activityId1,
        ),
      ]);
      /** End Setup */

      // Retrieve seqJson
      const getSequenceSeqJsonResponse = await getSequenceSeqJson(
        graphqlClient,
        'test00000',
        simulationArtifactPk.simulationDatasetId,
      );

      if (getSequenceSeqJsonResponse.status !== FallibleStatus.SUCCESS) {
        throw getSequenceSeqJsonResponse.errors;
      }

      expect(getSequenceSeqJsonResponse.seqJson.id).toBe('test00000');
      expect(getSequenceSeqJsonResponse.seqJson.metadata).toEqual({
        planId: planId,
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
        timeSorted: true,
      });

      expect(getSequenceSeqJsonResponse.seqJson.steps).toEqual([
        {
          type: 'command',
          stem: 'PICK_BANANA',
          time: { tag: '2020-001T00:00:01.000', type: TimingTypes.ABSOLUTE }, // R00:00:01 from plan start
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
        {
          type: 'command',
          stem: 'GROW_BANANA',
          time: { tag: '2023-091T04:20:00.000', type: TimingTypes.ABSOLUTE },
          args: [
            { value: 10, name: 'quantity', type: 'number' },
            { value: 7200, name: 'durationSecs', type: 'number' },
          ],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
        {
          type: 'command',
          stem: 'ADD_WATER',
          time: { tag: '2023-091T08:19:00.000', type: TimingTypes.ABSOLUTE },
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
      ]);

      /** Begin Cleanup */
      await removeSequence(graphqlClient, sequencePk);
      await removeExpansionRun(graphqlClient, expansionRunPk);
      await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
      await Promise.all([removeActivityDirective(graphqlClient, activityId1, planId)]);
      await removeExpansion(graphqlClient, expansionId);
      await removeExpansionSet(graphqlClient, expansionSetId);
      /** End Cleanup */
    }, 30000);

    it('should not sort if the expansion contains complete commands', async () => {
      const expansionId = await insertExpansion(
        graphqlClient,
        'GrowBanana',
        `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        A\`2023-091T08:19:00.000\`.ADD_WATER,
        C.PICK_BANANA,
        A\`2023-091T04:20:00.000\`.GROW_BANANA({ quantity: 10, durationSecs: 7200 })
      ];
    }
    `,
        parcelId,
      );
      /** Begin Setup */
      // Create Expansion Set
      const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [expansionId]);

      // Create Activity Directives
      const [activityId1, activityId2, activityId3] = await Promise.all([
        insertActivityDirective(graphqlClient, planId, 'GrowBanana'),
        insertActivityDirective(graphqlClient, planId, 'PeelBanana', '30 minutes'),
        insertActivityDirective(graphqlClient, planId, 'ThrowBanana', '60 minutes'),
      ]);

      // Simulate Plan
      const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
      // Expand Plan to Sequence Fragments
      const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);
      // Create Sequence
      const sequencePk = await insertSequence(graphqlClient, {
        seqId: 'test00000',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      });
      // Link Activity Instances to Sequence
      await Promise.all([
        linkActivityInstance(graphqlClient, sequencePk, activityId1),
        linkActivityInstance(graphqlClient, sequencePk, activityId2),
        linkActivityInstance(graphqlClient, sequencePk, activityId3),
      ]);

      // Get the simulated activity ids
      const [simulatedActivityId1] = await Promise.all([
        convertActivityDirectiveIdToSimulatedActivityId(
          graphqlClient,
          simulationArtifactPk.simulationDatasetId,
          activityId1,
        ),
      ]);
      /** End Setup */

      // Retrieve seqJson
      const getSequenceSeqJsonResponse = await getSequenceSeqJson(
        graphqlClient,
        'test00000',
        simulationArtifactPk.simulationDatasetId,
      );

      if (getSequenceSeqJsonResponse.status !== FallibleStatus.SUCCESS) {
        throw getSequenceSeqJsonResponse.errors;
      }

      expect(getSequenceSeqJsonResponse.seqJson.id).toBe('test00000');
      expect(getSequenceSeqJsonResponse.seqJson.metadata).toEqual({
        planId: planId,
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
        timeSorted: false,
      });

      expect(getSequenceSeqJsonResponse.seqJson.steps).toEqual([
        {
          type: 'command',
          stem: 'ADD_WATER',
          time: { tag: '2023-091T08:19:00.000', type: TimingTypes.ABSOLUTE },
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
        {
          type: 'command',
          stem: 'PICK_BANANA',
          time: { type: TimingTypes.COMMAND_COMPLETE },
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
        {
          type: 'command',
          stem: 'GROW_BANANA',
          time: { tag: '2023-091T04:20:00.000', type: TimingTypes.ABSOLUTE },
          args: [
            { value: 10, name: 'quantity', type: 'number' },
            { value: 7200, name: 'durationSecs', type: 'number' },
          ],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
      ]);

      /** Begin Cleanup */
      await removeSequence(graphqlClient, sequencePk);
      await removeExpansionRun(graphqlClient, expansionRunPk);
      await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
      await Promise.all([
        removeActivityDirective(graphqlClient, activityId1, planId),
        removeActivityDirective(graphqlClient, activityId2, planId),
        removeActivityDirective(graphqlClient, activityId3, planId),
      ]);
      await removeExpansion(graphqlClient, expansionId);
      await removeExpansionSet(graphqlClient, expansionSetId);
      /** End Cleanup */
    }, 30000);

    it('should not sort if the expansion contains epoch relative commands', async () => {
      const expansionId = await insertExpansion(
        graphqlClient,
        'GrowBanana',
        `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        A\`2023-091T08:19:00.000\`.ADD_WATER,
        E\`000T04:00:00.000\`.PICK_BANANA,
        A\`2023-091T04:20:00.000\`.GROW_BANANA({ quantity: 10, durationSecs: 7200 })
      ];
    }
    `,
        parcelId,
      );
      /** Begin Setup */
      // Create Expansion Set
      const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [expansionId]);

      // Create Activity Directives
      const [activityId1, activityId2, activityId3] = await Promise.all([
        insertActivityDirective(graphqlClient, planId, 'GrowBanana'),
        insertActivityDirective(graphqlClient, planId, 'PeelBanana', '30 minutes'),
        insertActivityDirective(graphqlClient, planId, 'ThrowBanana', '60 minutes'),
      ]);

      // Simulate Plan
      const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
      // Expand Plan to Sequence Fragments
      const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);
      // Create Sequence
      const sequencePk = await insertSequence(graphqlClient, {
        seqId: 'test00000',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      });
      // Link Activity Instances to Sequence
      await Promise.all([
        linkActivityInstance(graphqlClient, sequencePk, activityId1),
        linkActivityInstance(graphqlClient, sequencePk, activityId2),
        linkActivityInstance(graphqlClient, sequencePk, activityId3),
      ]);

      // Get the simulated activity ids
      const [simulatedActivityId1] = await Promise.all([
        convertActivityDirectiveIdToSimulatedActivityId(
          graphqlClient,
          simulationArtifactPk.simulationDatasetId,
          activityId1,
        ),
      ]);
      /** End Setup */

      // Retrieve seqJson
      const getSequenceSeqJsonResponse = await getSequenceSeqJson(
        graphqlClient,
        'test00000',
        simulationArtifactPk.simulationDatasetId,
      );

      if (getSequenceSeqJsonResponse.status !== FallibleStatus.SUCCESS) {
        throw getSequenceSeqJsonResponse.errors;
      }

      expect(getSequenceSeqJsonResponse.seqJson.id).toBe('test00000');
      expect(getSequenceSeqJsonResponse.seqJson.metadata).toEqual({
        planId: planId,
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
        timeSorted: false,
      });

      expect(getSequenceSeqJsonResponse.seqJson.steps).toEqual([
        {
          type: 'command',
          stem: 'ADD_WATER',
          time: { tag: '2023-091T08:19:00.000', type: TimingTypes.ABSOLUTE },
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
        {
          type: 'command',
          stem: 'PICK_BANANA',
          time: { tag: '04:00:00.000', type: TimingTypes.EPOCH_RELATIVE },
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
        {
          type: 'command',
          stem: 'GROW_BANANA',
          time: { tag: '2023-091T04:20:00.000', type: TimingTypes.ABSOLUTE },
          args: [
            { value: 10, name: 'quantity', type: 'number' },
            { value: 7200, name: 'durationSecs', type: 'number' },
          ],
          metadata: { simulatedActivityId: simulatedActivityId1 },
        },
      ]);

      /** Begin Cleanup */
      await removeSequence(graphqlClient, sequencePk);
      await removeExpansionRun(graphqlClient, expansionRunPk);
      await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
      await Promise.all([
        removeActivityDirective(graphqlClient, activityId1, planId),
        removeActivityDirective(graphqlClient, activityId2, planId),
        removeActivityDirective(graphqlClient, activityId3, planId),
      ]);
      await removeExpansion(graphqlClient, expansionId);
      await removeExpansionSet(graphqlClient, expansionSetId);
      /** End Cleanup */
    }, 30000);
  });

  it('Channel and Parameter Dictionary', async () => {
    /** Begin Setup */
    // Create Expansion Set
    const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [
      expansionId1,
      expansionId2,
      expansionId5,
    ]);

    // Create Activity Directives
    const [activityId1, activityId2] = await Promise.all([
      insertActivityDirective(graphqlClient, planId, 'BiteBanana', '90 minutes'), // non-existent expansion

      insertActivityDirective(graphqlClient, planId, 'BananaNap', '230 minutes'),
    ]);

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
    // Expand Plan to Sequence Fragments
    const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);
    // Create Sequence
    const [sequencePk1, sequencePk2] = await Promise.all([
      insertSequence(graphqlClient, {
        seqId: 'test00000',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      }),
      insertSequence(graphqlClient, {
        seqId: 'test00001',
        simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      }),
    ]);
    // Link Activity Instances to Sequence
    await Promise.all([
      linkActivityInstance(graphqlClient, sequencePk1, activityId1),
      linkActivityInstance(graphqlClient, sequencePk2, activityId2),
    ]);

    // Get the simulated activity ids
    const [
      _simulatedActivityId1, // No expansion, so no check required on this one
      simulatedActivityId2,
    ] = await Promise.all([
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId1,
      ),
      convertActivityDirectiveIdToSimulatedActivityId(
        graphqlClient,
        simulationArtifactPk.simulationDatasetId,
        activityId2,
      ),
    ]);

    /** End Setup */

    // Retrieve seqJson
    const getSequenceSeqJsonResponse = await getSequenceSeqJsonBulk(graphqlClient, [
      { seqId: 'test00000', simulationDatasetId: simulationArtifactPk.simulationDatasetId },
      { seqId: 'test00001', simulationDatasetId: simulationArtifactPk.simulationDatasetId },
    ]);

    const secondSequence = getSequenceSeqJsonResponse[1]!;

    if (secondSequence.status !== FallibleStatus.SUCCESS) {
      throw secondSequence.errors;
    }

    expect(secondSequence.seqJson.id).toBe('test00001');
    expect(secondSequence.seqJson.metadata).toEqual({
      planId: planId,
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
      timeSorted: false,
    });
    expect(secondSequence.seqJson.steps).toEqual([
      {
        args: [
          {
            name: 'echo_string',
            type: 'string',
            value: 'Telemetry Name: BAKE_STATE',
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId2 },
        stem: 'ECHO',
        time: { type: 'COMMAND_COMPLETE' },
        type: 'command',
      },
      {
        args: [
          {
            name: 'echo_string',
            type: 'string',
            value: 'Parameter Name: BANANA_COLOR_RATE',
          },
        ],
        metadata: { simulatedActivityId: simulatedActivityId2 },
        stem: 'ECHO',
        time: {
          type: 'COMMAND_COMPLETE',
        },
        type: 'command',
      },
    ]);

    /** Begin Cleanup */
    await Promise.all([removeSequence(graphqlClient, sequencePk1), removeSequence(graphqlClient, sequencePk2)]);
    await removeExpansionRun(graphqlClient, expansionRunPk);
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    await Promise.all([
      removeActivityDirective(graphqlClient, activityId1, planId),
      removeActivityDirective(graphqlClient, activityId2, planId),
    ]);
    await removeExpansionSet(graphqlClient, expansionSetId);
    await removeExpansion(graphqlClient, expansionId5);
    /** End Cleanup */
  }, 30000);
});

it('should provide start, end, and computed attributes on activities', async () => {
  // Setup

  const activityId = await insertActivityDirective(graphqlClient, planId, 'BakeBananaBread', '1 hours', {
    tbSugar: 1,
    glutenFree: false,
    temperature: 350,
  });
  const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
  const expansionId = await insertExpansion(
    graphqlClient,
    'BakeBananaBread',
    `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        A(props.activityInstance.startTime).BAKE_BREAD,
        A(props.activityInstance.endTime).BAKE_BREAD,
        C.ECHO({ echo_string: "Computed attributes: " + props.activityInstance.attributes.computed }),
      ];
    }
    `,
    parcelId,
  );

  const expansionSet0Id = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [expansionId]);
  const expansionRunPk = await expandLegacy(graphqlClient, expansionSet0Id, simulationArtifactPk.simulationDatasetId);
  const sequencePk = await insertSequence(graphqlClient, {
    seqId: 'test00000',
    simulationDatasetId: simulationArtifactPk.simulationDatasetId,
  });
  await linkActivityInstance(graphqlClient, sequencePk, activityId);

  const simulatedActivityId3 = await convertActivityDirectiveIdToSimulatedActivityId(
    graphqlClient,
    simulationArtifactPk.simulationDatasetId,
    activityId,
  );

  const getSequenceSeqJsonResponse = await getSequenceSeqJson(
    graphqlClient,
    'test00000',
    simulationArtifactPk.simulationDatasetId,
  );

  if (getSequenceSeqJsonResponse.status !== FallibleStatus.SUCCESS) {
    throw getSequenceSeqJsonResponse.errors;
  }

  expect(getSequenceSeqJsonResponse.seqJson.id).toBe('test00000');
  expect(getSequenceSeqJsonResponse.seqJson.metadata).toEqual({
    planId: planId,
    simulationDatasetId: simulationArtifactPk.simulationDatasetId,
    timeSorted: false,
  });
  expect(getSequenceSeqJsonResponse.seqJson.steps).toEqual([
    {
      type: 'command',
      stem: 'BAKE_BREAD',
      time: { type: TimingTypes.ABSOLUTE, tag: '2020-001T01:00:00.000' },
      args: [],
      metadata: { simulatedActivityId: simulatedActivityId3 },
    },
    {
      type: 'command',
      stem: 'BAKE_BREAD',
      time: { type: TimingTypes.ABSOLUTE, tag: '2020-001T01:00:00.000' },
      args: [],
      metadata: { simulatedActivityId: simulatedActivityId3 },
    },
    {
      type: 'command',
      stem: 'ECHO',
      time: { type: 'COMMAND_COMPLETE' },
      args: [{ name: 'echo_string', type: 'string', value: 'Computed attributes: 198' }],
      metadata: { simulatedActivityId: simulatedActivityId3 },
    },
  ]);

  // Cleanup
  {
    await removeSequence(graphqlClient, sequencePk);
    await removeExpansionRun(graphqlClient, expansionRunPk);
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    await Promise.all([removeActivityDirective(graphqlClient, activityId, planId)]);
    await removeExpansion(graphqlClient, expansionId);
    await removeExpansionSet(graphqlClient, expansionSet0Id);
  }
}, 30000);
