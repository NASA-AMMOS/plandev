import type { GraphQLClient } from 'graphql-request';
import { insertExpansion, removeExpansion } from '../testUtils/Expansion';
import { expansionBatchLoader } from '../../src/lib/batchLoaders/expansionBatchLoader';
import { getGraphQLClient } from '../testUtils/testUtils';
import { insertDictionary, removeDictionary } from '../testUtils/Dictionary';
import { insertParcel, removeParcel } from '../testUtils/Parcel';
import { DictionaryType } from '../../src/types/types';

let graphqlClient: GraphQLClient;
let expansionId: number;
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
      'expansionBatchLoaderTestParcel',
    )
  ).parcelId;

  expansionId = await insertExpansion(
    graphqlClient,
    'PeelBanana',
    `export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
  return [
    PREHEAT_OVEN({temperature: 70}),
    PREPARE_LOAF(50, false),
    BAKE_BREAD,
  ];
}`,
    parcelId,
  );
});

afterAll(async () => {
  await removeExpansion(graphqlClient, expansionId);
  await removeParcel(graphqlClient, parcelId);
  await removeDictionary(graphqlClient, commandDictionaryId, DictionaryType.COMMAND);
  await removeDictionary(graphqlClient, channelDictionaryId, DictionaryType.CHANNEL);
  await removeDictionary(graphqlClient, parameterDictionaryId, DictionaryType.PARAMETER);
});

it('should load expansion data', async () => {
  const expansions = await expansionBatchLoader({ graphqlClient })([{ expansionId }]);
  if (expansions[0] instanceof Error) {
    throw expansions[0];
  }
  expect(expansions[0]?.activityType).toBe('PeelBanana');
  expect(expansions[0]?.expansionLogic)
    .toBe(`export default function SingleCommandExpansion(props: { activityInstance: ActivityType }): ExpansionReturn {
  return [
    PREHEAT_OVEN({temperature: 70}),
    PREPARE_LOAF(50, false),
    BAKE_BREAD,
  ];
}`);
});
