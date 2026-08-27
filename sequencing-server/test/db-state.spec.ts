import type { GraphQLClient } from 'graphql-request';
import { getDictionary, insertDictionary, removeDictionary } from './testUtils/Dictionary';
import { getGraphQLClient } from './testUtils/testUtils.js';
import { DictionaryType } from '../src/types/types';
import { removeMissionModel, uploadMissionModel } from './testUtils/MissionModel';
import { getParcel, insertParcel, removeParcel } from './testUtils/Parcel';
let graphqlClient: GraphQLClient;
let missionModelId: number;
let commandDictonaryId: number;
let channelDictionaryId: number;
let parameterDictionaryId: number;
let parcelId: number;

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
  it('Deleting a Command Dictionary should delete its dependent parcel', async () => {
    // Command Dictionary is deleted
    await removeDictionary(graphqlClient, commandDictonaryId, DictionaryType.COMMAND);

    // Parcel should not exist
    const parcel = await getParcel(graphqlClient, parcelId);
    expect(parcel).toBeNull();
  }, 30000);

  it('Deleting a channel dictionary should clear the parcel channel dictionary reference', async () => {
    await removeDictionary(graphqlClient, channelDictionaryId, DictionaryType.CHANNEL);

    const parcel = await getParcel(graphqlClient, parcelId);
    expect(parcel).toMatchObject({
      id: parcelId,
      command_dictionary_id: commandDictonaryId,
      channel_dictionary_id: null,
    });
    expect(parcel?.parameter_dictionaries).toContainEqual({
      parameter_dictionary_id: parameterDictionaryId,
    });
  }, 30000);

  it('Deleting a parameter dictionary should remove its parcel association', async () => {
    await removeDictionary(graphqlClient, parameterDictionaryId, DictionaryType.PARAMETER);

    const parcel = await getParcel(graphqlClient, parcelId);
    expect(parcel).toMatchObject({
      id: parcelId,
      command_dictionary_id: commandDictonaryId,
      channel_dictionary_id: channelDictionaryId,
    });
    expect(parcel?.parameter_dictionaries).not.toContainEqual({
      parameter_dictionary_id: parameterDictionaryId,
    });
  }, 30000);

  it('Delete Parcel should not remove dictionaries', async () => {
    // Remove the parcel
    await removeParcel(graphqlClient, parcelId);

    // Parcel should not exist
    const parcel = await getParcel(graphqlClient, parcelId);
    expect(parcel).toBeNull();

    // Command, Channel, and Parameter Dictionary should exist
    const commandDictionary = await getDictionary(graphqlClient, commandDictonaryId, DictionaryType.COMMAND);
    expect(commandDictionary?.id).toEqual(commandDictonaryId);
    const channelDictionary = await getDictionary(graphqlClient, channelDictionaryId, DictionaryType.CHANNEL);
    expect(channelDictionary?.id).toEqual(channelDictionaryId);
    const parameterDictionary = await getDictionary(graphqlClient, parameterDictionaryId, DictionaryType.PARAMETER);
    expect(parameterDictionary?.id).toEqual(parameterDictionaryId);
  }, 30000);
});
