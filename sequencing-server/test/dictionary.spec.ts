import * as ampcs from '@nasa-jpl/aerie-ampcs';
import type { GraphQLClient } from 'graphql-request';
import {
  channelDictionaryString,
  commandDictionaryString,
  insertDictionary,
  parameterDictionaryString,
  removeDictionary,
} from './testUtils/Dictionary';
import { getGraphQLClient } from './testUtils/testUtils.js';
import { DictionaryType } from '../src/types/types';

let graphqlClient: GraphQLClient;

beforeAll(async () => {
  graphqlClient = await getGraphQLClient();
});

describe('upload dictionaries', () => {
  it('should upload a command dictionary and all of the fields should be populated correctly', async () => {
    // During the test we use a uuid for the mission so there's no conflicting command dictionaries.
    const { command } = await insertDictionary(graphqlClient, DictionaryType.COMMAND);

    expect(command.dictionary_path).toBe(`/usr/src/app/sequencing_file_store/${command.mission}/command_lib.${command.mission}.ts`);

    expect(command.parsed_json).toStrictEqual(
      ampcs.parse(commandDictionaryString.replace(/(Banana Nation|1.0.0.0)/g, command.mission)),
    );

    await removeDictionary(graphqlClient, command.id, DictionaryType.COMMAND);
  }, 30000);

  it('should upload a channel dictionary and all of the fields should be populated correctly', async () => {
    // During the test we use a uuid for the mission so there's no conflicting command dictionaries.
    const { channel } = await insertDictionary(graphqlClient, DictionaryType.CHANNEL);

    expect(channel.dictionary_path).toBe(`/usr/src/app/sequencing_file_store/${channel.mission}/channel_lib.${channel.mission}.ts`);

    expect(channel.parsed_json).toEqual(
      ampcs.parseChannelDictionary(channelDictionaryString.replace(/(Banana Nation|1.0.0.0)/g, channel.mission)),
    );

    await removeDictionary(graphqlClient, channel.id, DictionaryType.CHANNEL);
  }, 30000);

  it('should upload a parameter dictionary and all of the fields should be populated correctly', async () => {
    // During the test we use a uuid for the mission so there's no conflicting command dictionaries.
    const { parameter } = await insertDictionary(
      graphqlClient,
      DictionaryType.PARAMETER,
    );

    expect(parameter.dictionary_path).toBe(`/usr/src/app/sequencing_file_store/${parameter.mission}/parameter_lib.${parameter.mission}.ts`);

    expect(parameter.parsed_json).toEqual(
      ampcs.parseParameterDictionary(parameterDictionaryString.replace(/(Banana Nation|1.0.0.1)/g, parameter.mission)),
    );

    await removeDictionary(graphqlClient, parameter.id, DictionaryType.PARAMETER);
  }, 30000);
});
