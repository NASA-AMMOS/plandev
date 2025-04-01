import { gql, GraphQLClient } from 'graphql-request';
import { Status } from '../src/common.js';
import { insertDictionary, removeDictionary } from './testUtils/Dictionary';
import { getGraphQLClient } from './testUtils/testUtils.js';
import { DictionaryType } from '../src/types/types';

let graphqlClient: GraphQLClient;
let commandDictionaryId: number;
let channelDictionaryId: number;
let parameterDictionaryId: number;

beforeAll(async () => {
  graphqlClient = await getGraphQLClient();
  commandDictionaryId = (await insertDictionary(graphqlClient, DictionaryType.COMMAND)).command.id;
  channelDictionaryId = (await insertDictionary(graphqlClient, DictionaryType.CHANNEL)).channel.id;
  parameterDictionaryId = (await insertDictionary(graphqlClient, DictionaryType.PARAMETER)).parameter.id;
});

afterAll(async () => {
  await removeDictionary(graphqlClient, commandDictionaryId, DictionaryType.COMMAND);
  await removeDictionary(graphqlClient, channelDictionaryId, DictionaryType.CHANNEL);
  await removeDictionary(graphqlClient, parameterDictionaryId, DictionaryType.PARAMETER);
});

it('should return command types', async () => {
  const { getCommandTypeScript } = await graphqlClient.request<{
    getCommandTypeScript: {
      status: Status;
      typescriptFiles: {
        content: string;
        filePath: string;
      }[];
      reason: string;
    };
  }>(
    gql`
      query GetCommandTypes($commandDictionaryId: Int!) {
        getCommandTypeScript(commandDictionaryId: $commandDictionaryId) {
          status
          typescriptFiles {
            content
            filePath
          }
          reason
        }
      }
    `,
    {
      commandDictionaryId,
    },
  );

  expect(getCommandTypeScript.status).toBe(Status.SUCCESS);
  expect(getCommandTypeScript.typescriptFiles.length).toEqual(4);
  expect(getCommandTypeScript.typescriptFiles).toEqual([
    {
      content: expect.any(String),
      filePath: 'command-types.ts',
    },
    {
      content: expect.any(String),
      filePath: 'TemporalPolyfillTypes.ts',
    },
    {
      content: expect.any(String),
      filePath: 'ChannelTypes.ts',
    },
    {
      content: expect.any(String),
      filePath: 'ParameterTypes.ts',
    },
  ]);
  expect(getCommandTypeScript.typescriptFiles[0]!.content).toMatchSnapshot();
  expect(getCommandTypeScript.reason).toBe(null);
}, 10000);
