import type { GraphQLClient } from 'graphql-request';
import { insertActivityDirective } from './testUtils/ActivityDirective.js';
import { insertDictionary, removeDictionary } from './testUtils/Dictionary';
import {
  assignActivitiesByFilter,
  assignActivityToSequence,
  createSequence,
  createSequenceFilter,
  expandTemplates,
  insertSequenceTemplate,
  removeActivitySequenceAssignments,
  removeSequence,
} from './testUtils/Expansion.js';
import { removeMissionModel, uploadMissionModel } from './testUtils/MissionModel.js';
import { createPlan, removePlan } from './testUtils/Plan.js';
import { executeSimulation, removeSimulationArtifacts, updateSimulationBounds } from './testUtils/Simulation.js';
import { getGraphQLClient } from './testUtils/testUtils';
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
    expect(result).toEqual('CMD ENDTIME=2020-001/01:00:30 SETUP=2019-365/23:00:30\nCMD TEMPERATURE=350')

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
    expect(result).toEqual('CMD SETUP=2019-365/23:00:30') // only 1 activity. rest filtered by type and time!

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
      expect(result).toInclude(`CMD SEQUENCE=START_A AT=2020-001/00:00:00
CMD SEQUENCE=FINAL_A AT=2020-001/01:00:00
CMD SEQUENCE=START_B AT=2020-001/00:00:30
CMD SEQUENCE=FINAL_B AT=2020-001/00:00:30.030`) // expect interleaving!

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
  describe('Plaintext sequences', () => {
    let language = 'TEXT';

    it.only('should format dates and simply concatenate strings', async () => {
      let seqId = 'TextSequenceMerge';

      await insertSequenceTemplate(
        graphqlClient,
        `GrowBanana.tpl`,
        parcelId,
        missionModelId,
        `GrowBanana`,
        language,
        [
          `A{{ format-as-date startTime }} GROW_BANANA_1`,
          `A{{ format-as-date (add-time startTime attributes.arguments.growingDuration) }} GROW_BANANA_2`,
        ].join(`\n`),
      );

      await insertSequenceTemplate(
        graphqlClient,
        `DurationParameterActivity.tpl`,
        parcelId,
        missionModelId,
        `DurationParameterActivity`,
        language,
        [
          `A{{ format-as-date startTime }} DURATION_ACTIVITY_1`,
          `A{{ format-as-date (add-time startTime attributes.arguments.duration) }} DURATION_ACTIVITY_2`,
        ].join(`\n`),
      );

      const activityId_A = await insertActivityDirective(graphqlClient, planId, 'GrowBanana', '0 milliseconds');
      const activityId_B = await insertActivityDirective(
        graphqlClient,
        planId,
        'DurationParameterActivity',
        '30 seconds',
        { duration: 30000 },
      );

      // Simulate Plan
      const simulationArtifactPk = await executeSimulation(graphqlClient, planId);

      // Create Sequence
      const sequenceId = await createSequence(graphqlClient, seqId, simulationArtifactPk.simulationDatasetId);

      // Assign Activities Manually
      await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_A, seqId);
      await assignActivityToSequence(graphqlClient, simulationArtifactPk.simulationDatasetId, activityId_B, seqId);

      // Expand Plan
      const expandedTemplates: { [seqId: string]: string } = await expandTemplates(
        graphqlClient,
        missionModelId,
        [seqId],
        simulationArtifactPk.simulationDatasetId,
      );

      // verify results
      expect(sequenceId).toEqual(seqId);
      expect(expandedTemplates).not.toBeNull();

      const result = expandedTemplates[seqId];
      expect(result).toInclude(
        [
          `A2020-01-01T00:00:00Z GROW_BANANA_1`,
          `A2020-01-01T01:00:00Z GROW_BANANA_2`,
          `A2020-01-01T00:00:30Z DURATION_ACTIVITY_1`,
          `A2020-01-01T00:00:30.030000Z DURATION_ACTIVITY_2`,
        ].join(`\n`),
      );

      // Cleanup
      // remove sequence
      await removeSequence(graphqlClient, seqId);
      // remove simulation artifact pk
      await removeSimulationArtifacts(graphqlClient, simulationArtifactPk);
      // remove associations
      await removeActivitySequenceAssignments(graphqlClient, seqId);
    });
  });
})
