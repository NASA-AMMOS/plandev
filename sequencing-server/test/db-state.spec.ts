import type { GraphQLClient } from 'graphql-request';
import { getDictionary, insertDictionary, removeDictionary } from './testUtils/Dictionary';
import { getGraphQLClient } from './testUtils/testUtils.js';
import { DictionaryType } from '../src/types/types';
import { removeMissionModel, uploadMissionModel } from './testUtils/MissionModel';
import { getParcel, insertParcel, removeParcel } from './testUtils/Parcel';
import {
  getExpansion,
  getExpansionSet,
  insertExpansion,
  insertExpansionSet,
  removeExpansion,
  removeExpansionSet,
} from './testUtils/Expansion';
let graphqlClient: GraphQLClient;
let missionModelId: number;
let commandDictonaryId: number;
let channelDictionaryId: number;
let parameterDictionaryId: number;
let parcelId: number;
const expansion_rule: string = `export default function MyExpansion(props: {
      activityInstance: ActivityType,
      channelDictionary: ChannelDictionary | null
      parameterDictionaries : ParameterDictionary[]
    }): ExpansionReturn {
      const { activityInstance, channelDictionary, parameterDictionaries } = props;
      return [];
    }`;

beforeAll(async () => {
  graphqlClient = await getGraphQLClient();
  missionModelId = await uploadMissionModel(graphqlClient);
});

beforeEach(async () => {
  commandDictonaryId = (await insertDictionary(graphqlClient, DictionaryType.COMMAND)).command.id;
  channelDictionaryId = (await insertDictionary(graphqlClient, DictionaryType.CHANNEL)).channel.id;
  parameterDictionaryId = (await insertDictionary(graphqlClient, DictionaryType.PARAMETER)).parameter.id;
  parcelId = (
    await insertParcel(graphqlClient, commandDictonaryId, channelDictionaryId, parameterDictionaryId, 'db-parcel-test')
  ).parcelId;
}, 10000);

afterEach(async () => {
  await removeDictionary(graphqlClient, commandDictonaryId, DictionaryType.COMMAND);
  await removeDictionary(graphqlClient, channelDictionaryId, DictionaryType.CHANNEL);
  await removeDictionary(graphqlClient, parameterDictionaryId, DictionaryType.PARAMETER);
  await removeParcel(graphqlClient, parcelId);
});

afterAll(async () => {
  await removeMissionModel(graphqlClient, missionModelId);
});

describe('Sequencing DB State', () => {
  it('Delete Command Dictionary should remove parcel, and expansion set, but keep expansion rule', async () => {
    const expansionID = await insertExpansion(graphqlClient, 'BakeBananaBread', expansion_rule, parcelId);

    const setID = await insertExpansionSet(
      graphqlClient,
      parcelId,
      missionModelId,
      [expansionID],
      'db state test',
      'db-state set',
    );

    // Command Dictionary is deleted
    await removeDictionary(graphqlClient, commandDictonaryId, DictionaryType.COMMAND);

    // Parcel should not exist
    const parcel = await getParcel(graphqlClient, parcelId);
    expect(parcel).toBeNull();

    // Expansion Set should not exist
    const expansionSet = await getExpansionSet(graphqlClient, setID);
    expect(expansionSet).toBeNull();

    // expansion rule should exist, with no reference to the parcel
    const expansion = await getExpansion(graphqlClient, expansionID);
    expect(expansion.parcel_id).toBeNull();
    expect(expansion.id).toEqual(expansionID);

    // cleanup
    await removeExpansion(graphqlClient, expansionID);
  }, 30000);

  it('Delete channel or parameter Dictionary should NOT remove parcel, expansion set, and expansion rule', async () => {
    const expansionID = await insertExpansion(graphqlClient, 'BakeBananaBread', expansion_rule, parcelId);

    const setID = await insertExpansionSet(
      graphqlClient,
      parcelId,
      missionModelId,
      [expansionID],
      'db state test',
      'db-state set',
    );

    // Remove the channel and parameter dictionary
    await removeDictionary(graphqlClient, channelDictionaryId, DictionaryType.CHANNEL);
    await removeDictionary(graphqlClient, parameterDictionaryId, DictionaryType.PARAMETER);

    // Parcel should exist
    const parcel = await getParcel(graphqlClient, parcelId);
    expect(parcel?.id).toEqual(parcelId);

    // Expansion Set should exist
    const expansionSet = await getExpansionSet(graphqlClient, setID);
    expect(expansionSet?.id).toEqual(setID);
    expect(expansionSet?.parcel_id).toEqual(parcelId);
    expect(expansionSet?.mission_model_id).toEqual(missionModelId);
    expect(expansionSet?.expansion_rules[0]?.id).toEqual(expansionID);

    // expansion rule should exist, with a reference to the parcel
    const expansion = await getExpansion(graphqlClient, expansionID);
    expect(expansion.parcel_id).toEqual(parcelId);
    expect(expansion.id).toEqual(expansionID);

    // cleanup
    await removeExpansionSet(graphqlClient, setID);
    await removeExpansion(graphqlClient, expansionID);
  }, 30000);

  it('Delete Parcel should NOT remove dictionaries, or expansion rule, but remove expansion sets', async () => {
    const expansionID = await insertExpansion(graphqlClient, 'BakeBananaBread', expansion_rule, parcelId);

    const setID = await insertExpansionSet(
      graphqlClient,
      parcelId,
      missionModelId,
      [expansionID],
      'db state test',
      'db-state set',
    );

    // Remove the channel and parameter dictionary
    await removeParcel(graphqlClient, parcelId);

    // Parcel should exist
    const parcel = await getParcel(graphqlClient, parcelId);
    expect(parcel).toBeNull();

    // Command, Channel, and Parameter Dictionary should exist
    const commandDictionary = await getDictionary(graphqlClient, commandDictonaryId, DictionaryType.COMMAND);
    expect(commandDictionary?.id).toEqual(commandDictonaryId);
    const channelDictionary = await getDictionary(graphqlClient, channelDictionaryId, DictionaryType.CHANNEL);
    expect(channelDictionary?.id).toEqual(channelDictionaryId);
    const parameterDictionary = await getDictionary(graphqlClient, parameterDictionaryId, DictionaryType.PARAMETER);
    expect(parameterDictionary?.id).toEqual(parameterDictionaryId);

    // Expansion Set should not exist
    const expansionSet = await getExpansionSet(graphqlClient, setID);
    expect(expansionSet).toBeNull();

    // expansion rule should exist, with no reference to the parcel
    const expansion = await getExpansion(graphqlClient, expansionID);
    expect(expansion.parcel_id).toBeNull();
    expect(expansion.id).toEqual(expansionID);

    // cleanup
    await removeExpansion(graphqlClient, expansionID);
  }, 30000);
});
