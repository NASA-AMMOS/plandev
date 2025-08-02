import { ErrorWithStatusCode } from '../../utils/ErrorWithStatusCode.js';
import type { SequenceFilter } from '../filters/types.js';
import type { BatchLoader } from './index.js';
import { gql, GraphQLClient } from 'graphql-request';

export const sequenceFilterBatchLoader: BatchLoader<
  { filterId: number },
  SequenceFilterData,
  { graphqlClient: GraphQLClient }
> = opts => async keys => {
  const { sequence_filter } = await opts.graphqlClient.request<{
    sequence_filter: {
      id: number;
      filter: object;
      model_id: number;
      name: string;
    }[];
  }>(
    gql`
      query GetSequenceFilters($filterIds: [Int!]!) {
        sequence_filter(where: { id: { _in: $filterIds } }) {
          id
          filter
          model_id
          name
        }
      }
    `,
    {
      filterIds: keys.map(key => key.filterId),
    },
  );

  // TODO: This can be reduced to one-to-one; we want to allow the user to request multiple filters at once but that likely would send singular requests in sequence. Or maybe not?
  return Promise.all(
    keys.map(async ({ filterId }) => {
      const sequenceFilter = sequence_filter.find(({ id }) => id.toString() === filterId.toString());
      if (sequenceFilter === undefined) {
        return new ErrorWithStatusCode(`No sequence filter with id: ${filterId}`, 404);
      }

      return sequenceFilter;
    }),
  );
};

export type SequenceFilterData = {
  id: number,
  filter: SequenceFilter,
  model_id: number,
  name: string
};
