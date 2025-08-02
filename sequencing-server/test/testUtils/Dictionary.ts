import fs from 'node:fs';
import { gql, GraphQLClient } from 'graphql-request';
import { randomUUID } from 'node:crypto';
import type { CommandDictionary, ChannelDictionary, ParameterDictionary } from '@nasa-jpl/aerie-ampcs';
import { DictionaryType } from '../../src/types/types';

export const commandDictionaryString = fs.readFileSync(
  new URL('../../cdict/command_banananation.xml', import.meta.url).pathname,
  'utf-8',
);
export const channelDictionaryString = fs.readFileSync(
  new URL('../../cdict/channel_banananation.xml', import.meta.url).pathname,
  'utf-8',
);

export const parameterDictionaryString = fs.readFileSync(
  new URL('../../cdict/parameter_banananation.xml', import.meta.url).pathname,
  'utf-8',
);

export async function insertDictionary(
  graphqlClient: GraphQLClient,
  type: DictionaryType,
): Promise<{
  command: {id: number;
    dictionary_path: string;
    mission: string;
    version: string;
    parsed_json: CommandDictionary}
  parameter: {id: number;
    dictionary_path: string;
    mission: string;
    version: string;
    parsed_json: ParameterDictionary}
  channel: {id: number;
    dictionary_path: string;
    mission: string;
    version: string;
    parsed_json: ChannelDictionary}
}> {
  let dictonaryString = commandDictionaryString;
  switch (type) {
    case DictionaryType.CHANNEL:
      dictonaryString = channelDictionaryString;
      break;
    case DictionaryType.PARAMETER:
      dictonaryString = parameterDictionaryString;
      break;
  }
  const res = await graphqlClient.request<{
    uploadDictionary: {
      command: {id: number;
        dictionary_path: string;
        mission: string;
        version: string;
        parsed_json: CommandDictionary}
      parameter: {id: number;
        dictionary_path: string;
        mission: string;
        version: string;
        parsed_json: ParameterDictionary}
      channel: {id: number;
        dictionary_path: string;
        mission: string;
        version: string;
        parsed_json: ChannelDictionary}
    };
  }>(
    gql`
      mutation PutDictionary($dictionary: String!,  $persistDictionaryToFilesystem: Boolean!) {
        uploadDictionary(dictionary: $dictionary, persistDictionaryToFilesystem: $persistDictionaryToFilesystem) {
          command
          parameter
          channel
        }
      }
    `,
    {
      // Generate a UUID for the command dictionary name and version to avoid conflicts when testing.
      dictionary: dictonaryString.replace(/(Banana Nation|1.0.0.0|1.0.0.1)/g, randomUUID()),
      persistDictionaryToFilesystem: false
    },
  );

  return res.uploadDictionary;
}

export async function getDictionary(
  graphqlClient: GraphQLClient,
  dictionaryId: number,
  type: DictionaryType,
): Promise<{
  id: number;
  dictionary_path: string;
  mission: string;
  version: string;
  parsed_json: CommandDictionary | ChannelDictionary | ParameterDictionary;
}> {
  let dictonaryString = 'command_dictionary_by_pk';
  switch (type) {
    case DictionaryType.CHANNEL:
      dictonaryString = 'channel_dictionary_by_pk';
      break;
    case DictionaryType.PARAMETER:
      dictonaryString = 'parameter_dictionary_by_pk';
      break;
  }
  const res = await graphqlClient.request(
    gql`
      query GetDictionary($dictionaryId: Int!) {
        ${dictonaryString}(id: $dictionaryId) {
          id
          dictionary_path
          mission
          version
          parsed_json
        }
      }
    `,
    {
      dictionaryId,
    },
  );
  switch (type) {
    case DictionaryType.COMMAND:
      return res.command_dictionary_by_pk;
    case DictionaryType.CHANNEL:
      return res.channel_dictionary_by_pk;
    case DictionaryType.PARAMETER:
      return res.parameter_dictionary_by_pk;
  }
}

export async function removeDictionary(
  graphqlClient: GraphQLClient,
  dictionaryId: number,
  type: DictionaryType,
): Promise<void> {
  let mutationString: string = 'delete_command_dictionary_by_pk';
  switch (type) {
    case DictionaryType.CHANNEL:
      mutationString = 'delete_channel_dictionary_by_pk';
      break;
    case DictionaryType.PARAMETER:
      mutationString = 'delete_parameter_dictionary_by_pk';
      break;
  }
  return graphqlClient.request(
    gql`
        mutation DeleteCommandDictionary($dictionaryId: Int!) {
          ${mutationString}(id: $dictionaryId) {
            id
          }
        }
      `,
    {
      dictionaryId,
    },
  );
}
