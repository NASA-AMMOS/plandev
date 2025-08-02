import { gql, GraphQLClient } from 'graphql-request';
import { TimingTypes } from '../src/lib/codegen/CommandEDSLPreface.js';
import {
  convertActivityDirectiveIdToSimulatedActivityId,
  insertActivityDirective,
  removeActivityDirective,
} from './testUtils/ActivityDirective.js';
import { insertDictionary, removeDictionary } from './testUtils/Dictionary';
import {
  assignActivitiesByFilter,
  assignActivityToSequence,
  createSequence,
  createSequenceFilter,
  expandLegacy,
  expandTemplates,
  getExpandedSequence,
  getExpansionSet,
  insertExpansion,
  insertExpansionSet,
  insertSequenceTemplate,
  removeActivitySequenceAssignments,
  removeExpansion,
  removeExpansionRun,
  removeExpansionSet,
  removeSequence,
} from './testUtils/Expansion.js';
import { removeMissionModel, uploadMissionModel } from './testUtils/MissionModel.js';
import { createPlan, removePlan } from './testUtils/Plan.js';
import { executeSimulation, removeSimulationArtifacts, updateSimulationBounds } from './testUtils/Simulation.js';
import { getGraphQLClient, waitMs } from './testUtils/testUtils';
import { insertSequence, linkActivityInstance } from './testUtils/Sequence.js';
import { insertParcel, removeParcel } from './testUtils/Parcel';
import { DictionaryType } from '../src/types/types';

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
      'expansionTestParcel',
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

describe('legacy expansion', () => {
  let expansionId: number;
  let groundEventExpansion: number;
  let groundBlockExpansion: number;
  let activateLoadExpansion: number;

  beforeEach(async () => {
    expansionId = await insertExpansion(
      graphqlClient,
      'GrowBanana',
      `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        C.PREHEAT_OVEN({ temperature: 70 }),
        C.PREPARE_LOAF({ tb_sugar: 50, gluten_free: "FALSE" }),
        C.BAKE_BREAD,
      ];
    }
    `,
      parcelId,
    );

    groundEventExpansion = await insertExpansion(
      graphqlClient,
      'GrowBanana',
      `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        C.GROUND_EVENT("test")
      ];
    }
    `,
      parcelId,
    );

    groundBlockExpansion = await insertExpansion(
      graphqlClient,
      'GrowBanana',
      `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        C.GROUND_BLOCK("test")
      ];
    }
    `,
      parcelId,
    );

    activateLoadExpansion = await insertExpansion(
      graphqlClient,
      'BakeBananaBread',
      `export default function MyExpansion(props: {
          activityInstance: ActivityType
        }): ExpansionReturn {
          const { activityInstance } = props;
          return [
            A("2022-203T00:00:00").LOAD("BACKGROUND-A").ARGUMENTS(props.activityInstance.attributes.arguments.temperature),
            A("2022-204T00:00:00").ACTIVATE("BACKGROUND-B"),];
        }
        `,
      parcelId,
    );
  });

  afterEach(async () => {
    await removeExpansion(graphqlClient, expansionId);
    await removeExpansion(graphqlClient, groundEventExpansion);
    await removeExpansion(graphqlClient, groundBlockExpansion);
    await removeExpansion(graphqlClient, activateLoadExpansion);
  });

  it('should fail when the user creates an expansion set with a ground block', async () => {
    try {
      expect(await insertExpansionSet(graphqlClient, parcelId, missionModelId, [groundBlockExpansion])).toThrow();
    } catch (e) { }
  }, 30000);

  it('should fail when the user creates an expansion set with a ground event', async () => {
    try {
      expect(await insertExpansionSet(graphqlClient, parcelId, missionModelId, [groundEventExpansion])).toThrow();
    } catch (e) { }
  }, 30000);

  it('should expand load and activate steps ', async () => {
    const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [activateLoadExpansion]);

    const activityId = await insertActivityDirective(
      graphqlClient,
      planId,
      'BakeBananaBread',
      '30 seconds 0 milliseconds',
      { tbSugar: 1, glutenFree: false },
    );

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

    // Expand Plan
    const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);

    expect(expansionSetId).toBeGreaterThan(0);
    expect(expansionRunPk).toBeGreaterThan(0);

    const simulatedActivityId = await convertActivityDirectiveIdToSimulatedActivityId(
      graphqlClient,
      simulationArtifactPk.simulationDatasetId,
      activityId,
    );

    const expansionRunId = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);

    const { activity_instance_commands } = await graphqlClient.request<{
      activity_instance_commands: { commands: ReturnType<CommandStem['toSeqJson']>; errors: string[] }[];
    }>(
      gql`
        query getExpandedCommands($expansionRunId: Int!, $simulatedActivityId: Int!) {
          activity_instance_commands(
            where: {
              _and: { expansion_run_id: { _eq: $expansionRunId }, activity_instance_id: { _eq: $simulatedActivityId } }
            }
          ) {
            commands
            errors
          }
        }
      `,
      {
        expansionRunId,
        simulatedActivityId,
      },
    );

    expect(activity_instance_commands.length).toBe(1);
    if (activity_instance_commands[0]?.errors.length !== 0) {
      throw new Error(activity_instance_commands[0]?.errors.join('\n'));
    }
    expect(activity_instance_commands[0]?.commands).toEqual([
      {
        args: [
          {
            name: 'arg_0',
            type: 'number',
            value: 350,
          },
        ],
        metadata: { simulatedActivityId },
        sequence: 'BACKGROUND-A',
        time: {
          tag: '2022-203T00:00:00.000',
          type: 'ABSOLUTE',
        },
        type: 'load',
      },
      {
        metadata: { simulatedActivityId },
        sequence: 'BACKGROUND-B',
        time: {
          tag: '2022-204T00:00:00.000',
          type: 'ABSOLUTE',
        },
        type: 'activate',
      },
    ]);

    await removeExpansionRun(graphqlClient, expansionRunPk);
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    await removeActivityDirective(graphqlClient, activityId, planId);
    await removeExpansion(graphqlClient, expansionId);
    await removeExpansionSet(graphqlClient, expansionSetId);
  }, 30000);

  it('should allow an activity type and command to have the same name', async () => {
    const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [expansionId]);

    await insertActivityDirective(graphqlClient, planId, 'GrowBanana');

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

    // Expand Plan
    const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);

    expect(expansionSetId).toBeGreaterThan(0);
    expect(expansionRunPk).toBeGreaterThan(0);

    await removeExpansionRun(graphqlClient, expansionRunPk);
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    await removeExpansionSet(graphqlClient, expansionSetId);
  }, 30000);

  it('should throw an error if an activity instance goes beyond the plan duration', async () => {
    /** Begin Setup*/
    const activityId = await insertActivityDirective(graphqlClient, planId, 'GrowBanana', '1 days');
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
    // Wait 2s to allow the dataset to finish uploading
    // This is a bandaid method and should be fixed with a proper subscribe (or by also excluding the "uploading" state/changing it to wait for "success" state once 730 is complete)
    await waitMs(2000);
    const expansionId = await insertExpansion(
      graphqlClient,
      'GrowBanana',
      `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        R(props.activityInstance.startOffset).PREHEAT_OVEN({temperature: 70}),
        R(props.activityInstance.duration).PREHEAT_OVEN({temperature: 70}),
      ];
    }
    `,
      parcelId,
    );
    const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [expansionId]);
    const expansionRunId = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);

    const simulatedActivityId = await convertActivityDirectiveIdToSimulatedActivityId(
      graphqlClient,
      simulationArtifactPk.simulationDatasetId,
      activityId,
    );
    /** End Setup*/

    const { activity_instance_commands } = await graphqlClient.request<{
      activity_instance_commands: { commands: ReturnType<CommandStem['toSeqJson']>; errors: string[] }[];
    }>(
      gql`
        query getExpandedCommands($expansionRunId: Int!, $simulatedActivityId: Int!) {
          activity_instance_commands(
            where: {
              _and: { expansion_run_id: { _eq: $expansionRunId }, activity_instance_id: { _eq: $simulatedActivityId } }
            }
          ) {
            commands
            errors
          }
        }
      `,
      {
        expansionRunId,
        simulatedActivityId,
      },
    );

    expect(activity_instance_commands.length).toBe(1);
    expect(activity_instance_commands[0]?.errors).toEqual([
      {
        message: 'Duration is null',
      },
    ]);

    // Cleanup
    await removeActivityDirective(graphqlClient, activityId, planId);
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    await removeExpansion(graphqlClient, expansionId);
    await removeExpansionSet(graphqlClient, expansionSetId);
    await removeExpansionRun(graphqlClient, expansionRunId);
  }, 30000);

  it('start_offset undefined regression', async () => {
    /** Begin Setup*/
    const activityId = await insertActivityDirective(graphqlClient, planId, 'GrowBanana', '1 hours');
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
    const expansionId = await insertExpansion(
      graphqlClient,
      'GrowBanana',
      `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        R(props.activityInstance.startOffset).PREHEAT_OVEN({temperature: 70}),
        R(props.activityInstance.duration).PREHEAT_OVEN({temperature: 70}),
        R(props.activityInstance.attributes.arguments.growingDuration).PREHEAT_OVEN({temperature: 71}),
      ];
    }
    `,
      parcelId,
    );
    const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [expansionId]);
    const expansionRunId = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);

    const simulatedActivityId = await convertActivityDirectiveIdToSimulatedActivityId(
      graphqlClient,
      simulationArtifactPk.simulationDatasetId,
      activityId,
    );
    /** End Setup*/

    const { activity_instance_commands } = await graphqlClient.request<{
      activity_instance_commands: { commands: ReturnType<CommandStem['toSeqJson']>; errors: string[] }[];
    }>(
      gql`
        query getExpandedCommands($expansionRunId: Int!, $simulatedActivityId: Int!) {
          activity_instance_commands(
            where: {
              _and: { expansion_run_id: { _eq: $expansionRunId }, activity_instance_id: { _eq: $simulatedActivityId } }
            }
          ) {
            commands
            errors
          }
        }
      `,
      {
        expansionRunId,
        simulatedActivityId,
      },
    );

    expect(activity_instance_commands.length).toBe(1);
    if (activity_instance_commands[0]?.errors.length !== 0) {
      throw new Error(activity_instance_commands[0]?.errors.join('\n'));
    }
    expect(activity_instance_commands[0]?.commands).toEqual([
      {
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId },
        stem: 'PREHEAT_OVEN',
        time: { tag: '01:00:00.000', type: TimingTypes.COMMAND_RELATIVE },
        type: 'command',
      },
      {
        args: [{ value: 70, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId },
        stem: 'PREHEAT_OVEN',
        time: { tag: '01:00:00.000', type: TimingTypes.COMMAND_RELATIVE },
        type: 'command',
      },
      {
        args: [{ value: 71, name: 'temperature', type: 'number' }],
        metadata: { simulatedActivityId },
        stem: 'PREHEAT_OVEN',
        time: { tag: '01:00:00.000', type: TimingTypes.COMMAND_RELATIVE },
        type: 'command',
      },
    ]);

    // Cleanup
    await removeActivityDirective(graphqlClient, activityId, planId);
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    await removeExpansion(graphqlClient, expansionId);
    await removeExpansionSet(graphqlClient, expansionSetId);
    await removeExpansionRun(graphqlClient, expansionRunId);
  }, 30000);

  it('should save the expanded sequence on successful expansion', async () => {
    const expansionId = await insertExpansion(
      graphqlClient,
      'GrowBanana',
      `
    export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
      return [
        A\`2023-091T10:00:00.000\`.ADD_WATER,
        A\`2023-091T10:00:01.000\`.GROW_BANANA({ quantity: 10, durationSecs: 7200 })
      ];
    }
    `,
      parcelId,
    );
    // Create Expansion Set
    const expansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [expansionId]);

    // Create Activity Directives
    const [activityId] = await Promise.all([insertActivityDirective(graphqlClient, planId, 'GrowBanana')]);

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);
    // Create Sequence
    const sequencePk = await insertSequence(graphqlClient, {
      seqId: 'test00000',
      simulationDatasetId: simulationArtifactPk.simulationDatasetId,
    });
    // Link Activity Instances to Sequence
    await Promise.all([linkActivityInstance(graphqlClient, sequencePk, activityId)]);

    // Get the simulated activity ids
    const simulatedActivityId = await convertActivityDirectiveIdToSimulatedActivityId(
      graphqlClient,
      simulationArtifactPk.simulationDatasetId,
      activityId,
    );

    // Expand Plan to Sequence Fragments
    const expansionRunPk = await expandLegacy(graphqlClient, expansionSetId, simulationArtifactPk.simulationDatasetId);
    /** End Setup */

    const { expandedSequence } = await getExpandedSequence(graphqlClient, expansionRunPk, sequencePk.seqId);

    expect(expandedSequence).toEqual({
      id: 'test00000',
      metadata: { planId: planId, simulationDatasetId: simulationArtifactPk.simulationDatasetId, timeSorted: true },
      steps: [
        {
          args: [],
          metadata: { simulatedActivityId: simulatedActivityId },
          stem: 'ADD_WATER',
          time: { tag: '2023-091T10:00:00.000', type: 'ABSOLUTE' },
          type: 'command',
        },
        {
          args: [
            { name: 'quantity', type: 'number', value: 10 },
            { name: 'durationSecs', type: 'number', value: 7200 },
          ],
          metadata: { simulatedActivityId: simulatedActivityId },
          stem: 'GROW_BANANA',
          time: { tag: '2023-091T10:00:01.000', type: 'ABSOLUTE' },
          type: 'command',
        },
      ],
    });

    // Cleanup
    await removeActivityDirective(graphqlClient, activityId, planId);
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    await removeExpansion(graphqlClient, expansionId);
    await removeExpansionSet(graphqlClient, expansionSetId);
    await removeExpansionRun(graphqlClient, expansionRunPk);
  }, 30000);

  it('should handle optional name and descripton for expansion sets', async () => {
    const expansionId = await insertExpansion(
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
    const name = 'test name';
    const description = 'test desc';

    const testProvidedExpansionSetId = await insertExpansionSet(
      graphqlClient,
      parcelId,
      missionModelId,
      [expansionId],
      description,
      name,
    );
    expect(testProvidedExpansionSetId).not.toBeNull();
    expect(testProvidedExpansionSetId).toBeDefined();
    expect(testProvidedExpansionSetId).toBeNumber();

    const testProvidedResp = await getExpansionSet(graphqlClient, testProvidedExpansionSetId);
    expect(testProvidedResp).not.toBeNull();
    expect(testProvidedResp?.name).toBe(name);
    expect(testProvidedResp?.description).toBe(description);

    const testDefaultExpansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [expansionId]);
    expect(testDefaultExpansionSetId).not.toBeNull();
    expect(testDefaultExpansionSetId).toBeDefined();
    expect(testDefaultExpansionSetId).toBeNumber();

    const testDefaultResp = await getExpansionSet(graphqlClient, testDefaultExpansionSetId);
    expect(testDefaultResp).not.toBeNull();
    expect(testDefaultResp?.name).toBe('');
    expect(testDefaultResp?.description).toBe('');

    // Cleanup
    await removeExpansion(graphqlClient, expansionId);
    await removeExpansionSet(graphqlClient, testProvidedExpansionSetId);
    await removeExpansionSet(graphqlClient, testProvidedExpansionSetId);
  });

  it('should handle optional channel and parameter dictionaries', async () => {
    // Remove the optional dictionarys
    await removeDictionary(graphqlClient, channelDictionaryId, DictionaryType.CHANNEL);
    await removeDictionary(graphqlClient, parameterDictionaryId, DictionaryType.PARAMETER);

    const expansionId = await insertExpansion(
      graphqlClient,
      'GrowBanana',
      `
    export default function SingleCommandExpansion(props: {
    activityInstance: ActivityType,
    channelDictionary: ChannelDictionary | null,
    parameterDictionaries : ParameterDictionary[]
    }): ExpansionReturn {
      return [
        A\`2023-091T10:00:00.000\`.ADD_WATER,
        R\`04:00:00.000\`.GROW_BANANA({ quantity: 10, durationSecs: 7200 })
      ];
    }
    `,
      parcelId,
    );
    const name = 'test name';
    const description = 'test desc';

    const testProvidedExpansionSetId = await insertExpansionSet(
      graphqlClient,
      parcelId,
      missionModelId,
      [expansionId],
      description,
      name,
    );
    expect(testProvidedExpansionSetId).not.toBeNull();
    expect(testProvidedExpansionSetId).toBeDefined();
    expect(testProvidedExpansionSetId).toBeNumber();

    const testProvidedResp = await getExpansionSet(graphqlClient, testProvidedExpansionSetId);
    expect(testProvidedResp?.name).toBe(name);
    expect(testProvidedResp?.description).toBe(description);

    const testDefaultExpansionSetId = await insertExpansionSet(graphqlClient, parcelId, missionModelId, [expansionId]);
    expect(testDefaultExpansionSetId).not.toBeNull();
    expect(testDefaultExpansionSetId).toBeDefined();
    expect(testDefaultExpansionSetId).toBeNumber();

    const testDefaultResp = await getExpansionSet(graphqlClient, testDefaultExpansionSetId);
    expect(testDefaultResp?.name).toBe('');
    expect(testDefaultResp?.description).toBe('');

    // Cleanup
    await removeExpansion(graphqlClient, expansionId);
    await removeExpansionSet(graphqlClient, testProvidedExpansionSetId);
    await removeExpansionSet(graphqlClient, testProvidedExpansionSetId);
  });
});

describe('template expansion', () => {
  let language = "STOL"

  // test that does basic expansion, no handlebars
  it('should handle rudimentary template expansion', async () => {
    let seqId = "SequenceBasic"

    // insert a handlebar-less template for Activity Type A
    await insertSequenceTemplate(
      graphqlClient,
      `GrowBanana.tpl`,
      parcelId,
      missionModelId,
      `GrowBanana`,
      language,
      `CMD PARAM_GROW=-1`
    );

    // insert a handlebar-less template for Activity Type B
    await insertSequenceTemplate(
      graphqlClient,
      `BakeBananaBread.tpl`,
      parcelId,
      missionModelId,
      `BakeBananaBread`,
      language,
      `CMD PARAM_BAKE=-1`
    );

    const activityId_A = await insertActivityDirective(graphqlClient, planId, 'GrowBanana');
    const activityId_B = await insertActivityDirective(graphqlClient, planId, 'BakeBananaBread', '45 seconds 100 milliseconds', { temperature: 350, tbSugar: 1, glutenFree: true });

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

    // Create Sequence
    const sequenceId = await createSequence(graphqlClient, seqId, simulationArtifactPk.simulationDatasetId);

    // Assign Activities Manually
    // technically using directive IDs, but should match with span ids so its okay...
    await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_A, seqId);
    await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_B, seqId);

    // Expand Plan
    const expandedTemplates: { [seqId: string]: string } = await expandTemplates(graphqlClient, missionModelId, [seqId], simulationArtifactPk.simulationDatasetId);

    // verify results
    expect(sequenceId).toEqual(seqId);
    expect(expandedTemplates).not.toBeNull();

    const result = expandedTemplates[seqId]
    expect(result).toEqual('CMD PARAM_GROW=-1\nCMD PARAM_BAKE=-1')

    // Cleanup
    // remove sequence
    await removeSequence(graphqlClient, seqId)
    // remove simulation artifact pk
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    // remove associations
    await removeActivitySequenceAssignments(graphqlClient, seqId)
  });

  // test that accesses properties of activity
  it('should allow activity property access', async () => {
    let seqId = "SequenceProperties"

    // insert a template for Activity Type A
    await insertSequenceTemplate(
      graphqlClient,
      `GrowBanana.tpl`,
      parcelId,
      missionModelId,
      `GrowBanana`,
      language,
      `CMD DURATION={{attributes.arguments.growingDuration}} STARTTIME={{startTime}}`
    );

    // insert a template for Activity Type B
    await insertSequenceTemplate(
      graphqlClient,
      `BakeBananaBread.tpl`,
      parcelId,
      missionModelId,
      `BakeBananaBread`,
      language,
      `CMD TEMPERATURE={{attributes.arguments.temperature}} STARTTIME={{startTime}}`
    );

    const activityId_A = await insertActivityDirective(graphqlClient, planId, 'GrowBanana');
    const activityId_B = await insertActivityDirective(graphqlClient, planId, 'BakeBananaBread', '45 seconds 100 milliseconds', { temperature: 350, tbSugar: 1, glutenFree: true });

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

    // Create Sequence
    const sequenceId = await createSequence(graphqlClient, seqId, simulationArtifactPk.simulationDatasetId);

    // Assign Activities Manually
    // technically using directive IDs, but should match with span ids so its okay...
    await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_A, seqId);
    await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_B, seqId);

    // Expand Plan
    const expandedTemplates: { [seqId: string]: string } = await expandTemplates(graphqlClient, missionModelId, [seqId], simulationArtifactPk.simulationDatasetId);

    // verify results
    expect(sequenceId).toEqual(seqId);
    expect(expandedTemplates).not.toBeNull();

    const result = expandedTemplates[seqId]
    expect(result).toEqual('CMD DURATION=PT3600S STARTTIME=2020-01-01T00:00:30Z\nCMD TEMPERATURE=350 STARTTIME=2020-01-01T00:00:45.1Z')

    // Cleanup
    // remove sequence
    await removeSequence(graphqlClient, seqId)
    // remove simulation artifact pk
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    // remove associations
    await removeActivitySequenceAssignments(graphqlClient, seqId)
  });

  // test that uses helpers (addTime, for example)
  it('should utilize date-manipulating and formatting helpers correctly', async () => {
    let seqId = "SequenceHelpers"

    // insert a template for Activity Type A
    await insertSequenceTemplate(
      graphqlClient,
      `GrowBanana.tpl`,
      parcelId,
      missionModelId,
      `GrowBanana`,
      language,
      `CMD ENDTIME={{format-as-date (add-time startTime attributes.arguments.growingDuration)}} SETUP={{format-as-date (subtract-time startTime attributes.arguments.growingDuration)}}`
    );

    // insert a template for Activity Type B
    await insertSequenceTemplate(
      graphqlClient,
      `BakeBananaBread.tpl`,
      parcelId,
      missionModelId,
      `BakeBananaBread`,
      language,
      `CMD TEMPERATURE={{attributes.arguments.temperature}}`
    );

    const activityId_A = await insertActivityDirective(graphqlClient, planId, 'GrowBanana');
    const activityId_B = await insertActivityDirective(graphqlClient, planId, 'BakeBananaBread', '45 seconds 100 milliseconds', { temperature: 350, tbSugar: 1, glutenFree: true });

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

    // Create Sequence
    const sequenceId = await createSequence(graphqlClient, seqId, simulationArtifactPk.simulationDatasetId);

    // Assign Activities Manually
    // technically using directive IDs, but should match with span ids so its okay...
    await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_A, seqId);
    await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_B, seqId);

    // Expand Plan
    const expandedTemplates: { [seqId: string]: string } = await expandTemplates(graphqlClient, missionModelId, [seqId], simulationArtifactPk.simulationDatasetId);

    // verify results
    expect(sequenceId).toEqual(seqId);
    expect(expandedTemplates).not.toBeNull();

    const result = expandedTemplates[seqId]
    expect(result).toEqual('CMD ENDTIME=2020-001/01:00:30Z SETUP=2019-365/23:00:30Z\nCMD TEMPERATURE=350')

    // Cleanup
    // remove sequence
    await removeSequence(graphqlClient, seqId)
    // remove simulation artifact pk
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    // remove associations
    await removeActivitySequenceAssignments(graphqlClient, seqId)
  });

  // test filter functionality
  it('should filter activities correctly', async () => {
    let seqId = "SequenceHelpers"

    // insert a template for Activity Type A
    await insertSequenceTemplate(
      graphqlClient,
      `GrowBanana.tpl`,
      parcelId,
      missionModelId,
      `GrowBanana`,
      language,
      `CMD SETUP={{format-as-date (subtract-time startTime attributes.arguments.growingDuration)}}`
    );

    await insertActivityDirective(graphqlClient, planId, 'GrowBanana');
    await insertActivityDirective(graphqlClient, planId, 'BakeBananaBread', '30 seconds 100 milliseconds', { temperature: 350, tbSugar: 1, glutenFree: true });
    await insertActivityDirective(graphqlClient, planId, 'GrowBanana', '45 seconds 100 milliseconds');
    await insertActivityDirective(graphqlClient, planId, 'BakeBananaBread', '45 seconds 100 milliseconds', { temperature: 350, tbSugar: 1, glutenFree: true });

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

    // Create Sequence
    const sequenceId = await createSequence(graphqlClient, seqId, simulationArtifactPk.simulationDatasetId);

    // Create Filter
    const filterId = await createSequenceFilter(graphqlClient, { "static_types": ["GrowBanana"] }, "GrowBananaFilter", missionModelId)

    // Run Filter
    await assignActivitiesByFilter(graphqlClient, filterId, simulationArtifactPk.simulationDatasetId, seqId, "2020-001T00:00:00Z", "2020-001T00:00:40Z")

    // Expand Plan
    const expandedTemplates: { [seqId: string]: string } = await expandTemplates(graphqlClient, missionModelId, [seqId], simulationArtifactPk.simulationDatasetId);

    // verify results
    expect(sequenceId).toEqual(seqId);
    expect(expandedTemplates).not.toBeNull();

    const result = expandedTemplates[seqId]
    expect(result).toEqual('CMD SETUP=2019-365/23:00:30Z') // only 1 activity. rest filtered by type and time!

    // Cleanup
    // remove sequence
    await removeSequence(graphqlClient, seqId)
    // remove simulation artifact pk
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    // remove associations
    await removeActivitySequenceAssignments(graphqlClient, seqId)
  });

  // test that clearly shows how failed handlebar expansion is handled (invalid template)
  //    fails with a VERY nested error
  it('should fail correctly when an invalid template is used', async () => {
    let seqId = "SequenceFailBasic"

    // insert a flawed template for Activity Type A
    await insertSequenceTemplate(
      graphqlClient,
      `GrowBanana.tpl`,
      parcelId,
      missionModelId,
      `GrowBanana`,
      language,
      `CMD PARAM_GROW=-1 {{ param }`
    );

    const activityId_A = await insertActivityDirective(graphqlClient, planId, 'GrowBanana');

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

    // Create Sequence
    await createSequence(graphqlClient, seqId, simulationArtifactPk.simulationDatasetId);

    // Assign Activities Manually
    // technically using directive IDs, but should match with span ids so its okay...
    await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_A, seqId);

    // Expand Plan
    try {
      await expandTemplates(graphqlClient, missionModelId, [seqId], simulationArtifactPk.simulationDatasetId);
    }
    catch (e) {
      // verify results
      let e_casted: { response: { errors: { extensions: { internal: { response: { body: { extensions: { stack: any } } } } } }[] } } = e as ({ response: { errors: { extensions: { internal: { response: { body: { extensions: { stack: any } } } } } }[] } })
      let error = e_casted?.response?.errors[0]?.extensions.internal.response.body.extensions.stack
      expect(error).toInclude(`Expecting 'CLOSE_RAW_BLOCK', 'CLOSE', 'CLOSE_UNESCAPED', 'OPEN_SEXPR', 'CLOSE_SEXPR', 'ID', 'OPEN_BLOCK_PARAMS', 'STRING', 'NUMBER', 'BOOLEAN', 'UNDEFINED', 'NULL', 'DATA', 'SEP', got 'INVALID'`)
    }

    // Cleanup
    // remove sequence
    await removeSequence(graphqlClient, seqId)
    // remove simulation artifact pk
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    // remove associations
    await removeActivitySequenceAssignments(graphqlClient, seqId)
  });

  // TODO: test that fails because multiple languages were used in templates for the same model
  it('should fail correctly when multiple languages are used for the same model', async () => {
    let seqId = "SequenceFailMultiLang"

    // insert a flawed template for Activity Type A
    await insertSequenceTemplate(
      graphqlClient,
      `GrowBanana.tpl`,
      parcelId,
      missionModelId,
      `GrowBanana`,
      `STOL`,
      `CMD A`
    );

    // insert a flawed template for Activity Type B
    await insertSequenceTemplate(
      graphqlClient,
      `ThrowBanana.tpl`,
      parcelId,
      missionModelId,
      `ThrowBanana`,
      `SeqN`,
      `CMD B`
    );

    const activityId_A = await insertActivityDirective(graphqlClient, planId, 'GrowBanana');
    const activityId_B = await insertActivityDirective(graphqlClient, planId, 'ThrowBanana');

    // Simulate Plan
    const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

    // Create Sequence
    await createSequence(graphqlClient, seqId, simulationArtifactPk.simulationDatasetId);

    // Assign Activities Manually
    // technically using directive IDs, but should match with span ids so its okay...
    await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_A, seqId);
    await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_B, seqId);

    // Expand Plan
    try {
      await expandTemplates(graphqlClient, missionModelId, [seqId], simulationArtifactPk.simulationDatasetId);
    }
    catch (e) {
      // verify results
      let e_casted: { response: { errors: { extensions: { internal: { response: { body: { extensions: { stack: any } } } } } }[] } } = e as ({ response: { errors: { extensions: { internal: { response: { body: { extensions: { stack: any } } } } } }[] } })
      let error = e_casted?.response?.errors[0]?.extensions.internal.response.body.extensions.stack
      expect(error).toInclude(`using different languages (STOL,SeqN)`)
    }

    // Cleanup
    // remove sequence
    await removeSequence(graphqlClient, seqId)
    // remove simulation artifact pk
    await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
    // remove associations
    await removeActivitySequenceAssignments(graphqlClient, seqId)
  });

  // SeqN-specific tests
  describe('SeqN-specific functionality', () => {
    let language = "SeqN"

    // simple test that just demonstrates that relative times get converted to absolute times, and we can use activity arguments
    it('should handle rudimentary SeqN', async () => {
      let seqId = "SeqNSequenceBasic"

      // insert a template for Activity Type A
      await insertSequenceTemplate(
        graphqlClient,
        `GrowBanana.tpl`,
        parcelId,
        missionModelId,
        `GrowBanana`,
        language,
        `R00:00:00 GROW_BANANA {{attributes.arguments.quantity}}
R00:00:01 CMD_ECHO "STARTING"
R00:01:00 CMD_ECHO "ENDING"`
      );

      const activityId_A = await insertActivityDirective(graphqlClient, planId, 'GrowBanana');

      // Simulate Plan
      const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

      // Create Sequence
      const sequenceId = await createSequence(graphqlClient, seqId, simulationArtifactPk.simulationDatasetId);

      // Assign Activities Manually
      // technically using directive IDs, but should match with span ids so its okay...
      await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_A, seqId);

      // Expand Plan
      const expandedTemplates: { [seqId: string]: string } = await expandTemplates(graphqlClient, missionModelId, [seqId], simulationArtifactPk.simulationDatasetId);

      // verify results
      expect(sequenceId).toEqual(seqId);
      expect(expandedTemplates).not.toBeNull();

      const result = expandedTemplates[seqId]
      expect(result).toInclude(`A2020-001T00:00:30.000 GROW_BANANA 1
A2020-001T00:00:31.000 CMD_ECHO "STARTING"
A2020-001T00:01:31.000 CMD_ECHO "ENDING"`)

      // Cleanup
      // remove sequence
      await removeSequence(graphqlClient, seqId)
      // remove simulation artifact pk
      await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
      // remove associations
      await removeActivitySequenceAssignments(graphqlClient, seqId)
    });

    // test that interleaves two activities (with relative times)
    it('should merge different activities\' SeqN correctly', async () => {
      let seqId = "SeqNSequenceMerge"

      // insert a template for Activity Type A
      await insertSequenceTemplate(
        graphqlClient,
        `GrowBanana.tpl`,
        parcelId,
        missionModelId,
        `GrowBanana`,
        language,
        `R00:00:01 CMD_ECHO "This is activity A starting"
R00:01:00 CMD_ECHO "This is activity A ending"`
      );

      // insert a template for activity type B
      // insert a handlebar-less template for Activity Type A
      await insertSequenceTemplate(
        graphqlClient,
        `BakeBananaBread.tpl`,
        parcelId,
        missionModelId,
        `BakeBananaBread`,
        language,
        `R00:00:01 CMD_ECHO "This is activity B starting"
R00:01:00 CMD_ECHO "This is activity B ending"`
      );

      const activityId_A = await insertActivityDirective(graphqlClient, planId, 'GrowBanana', '0 milliseconds');
      const activityId_B = await insertActivityDirective(graphqlClient, planId, 'BakeBananaBread', '30 seconds', { temperature: 350, tbSugar: 1, glutenFree: true });

      // Simulate Plan
      const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

      // Create Sequence
      const sequenceId = await createSequence(graphqlClient, seqId, simulationArtifactPk.simulationDatasetId);

      // Assign Activities Manually
      // technically using directive IDs, but should match with span ids so its okay...
      await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_A, seqId);
      await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_B, seqId);

      // Expand Plan
      const expandedTemplates: { [seqId: string]: string } = await expandTemplates(graphqlClient, missionModelId, [seqId], simulationArtifactPk.simulationDatasetId);

      // verify results
      expect(sequenceId).toEqual(seqId);
      expect(expandedTemplates).not.toBeNull();

      const result = expandedTemplates[seqId]
      expect(result).toInclude(`A2020-001T00:00:01.000 CMD_ECHO \"This is activity A starting\"
A2020-001T00:00:31.000 CMD_ECHO \"This is activity B starting\"
A2020-001T00:01:01.000 CMD_ECHO \"This is activity A ending\"
A2020-001T00:01:31.000 CMD_ECHO \"This is activity B ending\"`) // expect interleaving!

      // Cleanup
      // remove sequence
      await removeSequence(graphqlClient, seqId)
      // remove simulation artifact pk
      await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
      // remove associations
      await removeActivitySequenceAssignments(graphqlClient, seqId)
    });

    // one that has an absolute time baked into template (one without (its just hardcoded, could even be before the start time of the activity!!) and one with add-time)
    it('should handle absolute times in templates correctly', async () => {
      let seqId = "SeqNAbsoluteTimeSequence"

      // insert a template for Activity Type A
      await insertSequenceTemplate(
        graphqlClient,
        `GrowBanana.tpl`,
        parcelId,
        missionModelId,
        `GrowBanana`,
        language,
        `A2024-001T00:00:01 CMD_ECHO "This is activity A starting"
R00:01:00 CMD_ECHO "This is activity A ending"`
      );

      const activityId_A = await insertActivityDirective(graphqlClient, planId, 'GrowBanana', '0 milliseconds');

      // Simulate Plan
      const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

      // Create Sequence
      const sequenceId = await createSequence(graphqlClient, seqId, simulationArtifactPk.simulationDatasetId);

      // Assign Activities Manually
      // technically using directive IDs, but should match with span ids so its okay...
      await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_A, seqId);

      // Expand Plan
      const expandedTemplates: { [seqId: string]: string } = await expandTemplates(graphqlClient, missionModelId, [seqId], simulationArtifactPk.simulationDatasetId);

      // verify results
      expect(sequenceId).toEqual(seqId);
      expect(expandedTemplates).not.toBeNull();

      const result = expandedTemplates[seqId]
      expect(result).toInclude(`A2024-001T00:00:01.000 CMD_ECHO \"This is activity A starting\"
A2024-001T00:01:01.000 CMD_ECHO \"This is activity A ending\"`) // expect interleaving!

      // Cleanup
      // remove sequence
      await removeSequence(graphqlClient, seqId)
      // remove simulation artifact pk
      await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
      // remove associations
      await removeActivitySequenceAssignments(graphqlClient, seqId)
    });

    it('should handle absolute times in templates (using helpers) correctly', async () => {
      let seqId = "SeqNAbsoluteTimeHelperSequence"

      // insert a template for Activity Type A
      await insertSequenceTemplate(
        graphqlClient,
        `GrowBanana.tpl`,
        parcelId,
        missionModelId,
        `GrowBanana`,
        language,
        `A{{format-as-date startTime}} CMD_ECHO "This is activity A starting"
A{{format-as-date (add-time startTime attributes.arguments.growingDuration)}} CMD_ECHO "This is activity A ending"
R00:01:00 CMD_ECHO "This is activity A cooldown"`
      );

      const activityId_A = await insertActivityDirective(graphqlClient, planId, 'GrowBanana', '0 milliseconds');
      const activityId_B = await insertActivityDirective(graphqlClient, planId, 'BakeBananaBread', '30 seconds', { temperature: 350, tbSugar: 1, glutenFree: true });

      // Simulate Plan
      const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

      // Create Sequence
      const sequenceId = await createSequence(graphqlClient, seqId, simulationArtifactPk.simulationDatasetId);

      // Assign Activities Manually
      // technically using directive IDs, but should match with span ids so its okay...
      await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_A, seqId);
      await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_B, seqId);

      // Expand Plan
      const expandedTemplates: { [seqId: string]: string } = await expandTemplates(graphqlClient, missionModelId, [seqId], simulationArtifactPk.simulationDatasetId);

      // verify results
      expect(sequenceId).toEqual(seqId);
      expect(expandedTemplates).not.toBeNull();

      const result = expandedTemplates[seqId]
      expect(result).toInclude(`A2020-001T00:00:00.000 CMD_ECHO \"This is activity A starting\"
A2020-001T01:00:00.000 CMD_ECHO \"This is activity A ending\"
A2020-001T01:01:00.000 CMD_ECHO \"This is activity A cooldown\"`) // expect interleaving!

      // Cleanup
      // remove sequence
      await removeSequence(graphqlClient, seqId)
      // remove simulation artifact pk
      await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
      // remove associations
      await removeActivitySequenceAssignments(graphqlClient, seqId)
    });
  });

  // STOL/plaintext-specific tests
  describe('STOL/plaintext-specific functionality', () => {
    let language = "STOL"

    // test merging. illustrate that if we do a similar example to the seqn one, it WONT be sorted correctly because we do simple concatenation. Same as plaintext.
    it('should(n\'t) merge different activities\' STOL/plaintext correctly', async () => {
      let seqId = "STOLSequenceMerge"

      // insert a template for Activity Type A
      await insertSequenceTemplate(
        graphqlClient,
        `GrowBanana.tpl`,
        parcelId,
        missionModelId,
        `GrowBanana`,
        language,
        `CMD SEQUENCE=START_A AT={{ format-as-date startTime }}
CMD SEQUENCE=FINAL_A AT={{ format-as-date (add-time startTime attributes.arguments.growingDuration) }}`
      );

      // insert a template for activity type B
      // insert a handlebar-less template for Activity Type A
      await insertSequenceTemplate(
        graphqlClient,
        `DurationParameterActivity.tpl`,
        parcelId,
        missionModelId,
        `DurationParameterActivity`,
        language,
        `CMD SEQUENCE=START_B AT={{ format-as-date startTime }}
CMD SEQUENCE=FINAL_B AT={{ format-as-date (add-time startTime attributes.arguments.duration) }}`
      );

      const activityId_A = await insertActivityDirective(graphqlClient, planId, 'GrowBanana', '0 milliseconds');
      const activityId_B = await insertActivityDirective(graphqlClient, planId, 'DurationParameterActivity', '30 seconds', { duration: 30000 });

      // Simulate Plan
      const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

      // Create Sequence
      const sequenceId = await createSequence(graphqlClient, seqId, simulationArtifactPk.simulationDatasetId);

      // Assign Activities Manually
      // technically using directive IDs, but should match with span ids so its okay...
      await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_A, seqId);
      await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_B, seqId);

      // Expand Plan
      const expandedTemplates: { [seqId: string]: string } = await expandTemplates(graphqlClient, missionModelId, [seqId], simulationArtifactPk.simulationDatasetId);

      // verify results
      expect(sequenceId).toEqual(seqId);
      expect(expandedTemplates).not.toBeNull();

      const result = expandedTemplates[seqId]
      expect(result).toInclude(`CMD SEQUENCE=START_A AT=2020-001/00:00:00Z
CMD SEQUENCE=FINAL_A AT=2020-001/01:00:00Z
CMD SEQUENCE=START_B AT=2020-001/00:00:30Z
CMD SEQUENCE=FINAL_B AT=2020-001/00:00:30.030Z`) // expect interleaving!

      // Cleanup
      // remove sequence
      await removeSequence(graphqlClient, seqId)
      // remove simulation artifact pk
      await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
      // remove associations
      await removeActivitySequenceAssignments(graphqlClient, seqId)
    });
  });

  // TODO: DAVID'S EDGE CASES
})
