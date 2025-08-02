import type { BatchLoader } from './index';
import { gql, GraphQLClient } from 'graphql-request';
import { ErrorWithStatusCode } from '../../utils/ErrorWithStatusCode.js';

export const sequenceTemplateBatchLoader: BatchLoader<
    { modelId: number },
    SequenceTemplate[],
    { graphqlClient: GraphQLClient}
> = opts => async keys => {
  const { sequence_templates } = await opts.graphqlClient.request<{ sequence_templates: SequenceTemplate[] }>(
      gql`
      query GetSequenceTemplates($modelIds: [Int!]!) {
        sequence_templates: sequence_template(where: { model_id: { _in: $modelIds }}) {
          id
          activity_type
          model_id
          parcel_id
          template_definition
          language
        }
      }
    `,
      {
        modelIds: keys.map(key => key.modelId)
      },
  );

  return Promise.all(
    keys.map(async ({ modelId }) => {
      const sequenceTemplates = sequence_templates.filter(sequence_template => sequence_template.model_id === modelId);
      if (sequenceTemplates === undefined || sequenceTemplates.length === 0) {
        return new ErrorWithStatusCode(`No sequence templates found with model id: ${modelId}`, 404);
      }

      return sequenceTemplates as SequenceTemplate[];
    }),
  );
};

export interface SequenceTemplate {
  id: number,
  activity_type: string,
  model_id: number,
  parcel_id: number,
  template_definition: string,
  language: string
}
