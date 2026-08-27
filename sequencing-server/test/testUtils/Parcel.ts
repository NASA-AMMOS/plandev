import { gql, GraphQLClient } from 'graphql-request';

export async function insertParcel(
  graphqlClient: GraphQLClient,
  commandDictionaryID: number,
  channelDictionaryID: number,
  parameterDictionaryID: number,
  parcelName: string,
): Promise<{
  parcelId: number;
}> {
  const res = await graphqlClient.request<{
    insert_parcel: {
      returning: {
        id: number;
      }[];
    };
  }>(
    gql`
      mutation PutParcel(
        $commandDictionaryID: Int!
        $channelDictionaryID: Int
        $parameterDictionaryID: Int
        $parcelName: String!
      ) {
        insert_parcel(
          objects: {
            command_dictionary_id: $commandDictionaryID
            channel_dictionary_id: $channelDictionaryID
            parameter_dictionaries: { data: { parameter_dictionary_id: $parameterDictionaryID } }
            name: $parcelName
          }
        ) {
          returning {
            id
          }
        }
      }
    `,
    {
      // Generate a UUID for the command dictionary name and version to avoid conflicts when testing.
      commandDictionaryID,
      channelDictionaryID,
      parameterDictionaryID,
      parcelName,
    },
  );

  return { parcelId: res.insert_parcel.returning[0]?.id ?? -1 };
}

export type Parcel = {
  id: number;
  name: string;
  command_dictionary_id: number;
  channel_dictionary_id: number | null;
  parameter_dictionaries: {
    parameter_dictionary_id: number;
  }[];
};
export async function getParcel(
  graphqlClient: GraphQLClient,
  parcelId: number,
): Promise<Parcel | null> {
  const res = await graphqlClient.request<{ parcel_by_pk: Parcel | null }>(
    gql`
      query GetParcel($parcelId: Int!) {
        parcel_by_pk(id: $parcelId) {
          id
          command_dictionary_id
          channel_dictionary_id
          parameter_dictionaries {
            parameter_dictionary_id
          }
          name
        }
      }
    `,
    {
      parcelId,
    },
  );
  return res.parcel_by_pk;
}

export async function removeParcel(graphqlClient: GraphQLClient, parcelId: number): Promise<void> {
  return graphqlClient.request(
    gql`
      mutation RemoveParcel($parcelId: Int!) {
        delete_parcel_by_pk(id: $parcelId) {
          id
        }
      }
    `,
    {
      parcelId,
    },
  );
}
