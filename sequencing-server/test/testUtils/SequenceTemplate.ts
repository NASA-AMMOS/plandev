import { gql, GraphQLClient } from 'graphql-request';
import type { ActivityLayerFilter } from '../../src/lib/filters/types';

export async function createSequence(
  graphqlClient: GraphQLClient,
  seqId: string,
  simulationDatasetId: number
): Promise<string> {
  const res = await graphqlClient.request<{
    createExpansionSequence: { seq_id: string }
  }>(
    gql`
      mutation CreateExpansionSequence($sequence: sequence_insert_input!) {
        createExpansionSequence: insert_sequence_one(object: $sequence) {
          seq_id
        }
      }
    `,
    {
      sequence: {
        metadata: {},
        seq_id: seqId,
        simulation_dataset_id: simulationDatasetId
      }
    },
  );
  return res.createExpansionSequence.seq_id;
}

export async function assignActivityToSequence(
  graphqlClient: GraphQLClient,
  simulationDatasetId: number,
  simulatedActivityId: number,
  seqId: string,
): Promise<number> {
  const res = await graphqlClient.request<{
    sequence: { id: number }
  }>(
    gql`
      mutation InsertSequenceToActivity($input: sequence_to_simulated_activity_insert_input!) {
        sequence: insert_sequence_to_simulated_activity_one(
          object: $input,
          on_conflict: {
            constraint: sequence_to_simulated_activity_primary_key,
            update_columns: [seq_id]
          }
        ) {
          seq_id
        }
      }
    `,
    {
      input: {
        seq_id: seqId,
        simulated_activity_id: simulatedActivityId,
        simulation_dataset_id: simulationDatasetId,
      }
    },
  );
  return res.sequence.id;
}

export async function removeSequence(
  graphqlClient: GraphQLClient,
  seqId: string,
): Promise<number> {
  const res = await graphqlClient.request<{
    delete_sequence: { affected_rows: number }
  }>(
    gql`
      mutation RemoveSequence($seqId: String!) {
        delete_sequence(where: { seq_id: {_eq: $seqId}}) {
          affected_rows
        }
      }
    `,
    {
      seqId
    },
  );
  return res.delete_sequence.affected_rows;
}

export async function removeActivitySequenceAssignments(
  graphqlClient: GraphQLClient,
  seqId: string,
): Promise<number> {
  const res = await graphqlClient.request<{
    delete_sequence_to_simulated_activity: { affected_rows: number }
  }>(
    gql`
      mutation RemoveActivitySequenceAssignment($seqId: String!) {
        delete_sequence_to_simulated_activity(where: { seq_id: {_eq: $seqId}}) {
          affected_rows
        }
      }
    `,
    {
      seqId
    },
  );
  return res.delete_sequence_to_simulated_activity.affected_rows;
}


export async function insertSequenceTemplate(
  graphqlClient: GraphQLClient,
  name: string,
  parcelId: number,
  modelId: number,
  activityTypeName: string,
  language: string,
  templateDefinition: string
): Promise<number> {
  const res = await graphqlClient.request<{
    addTemplate: { id: number }
  }>(
    gql`
      mutation CreateSequenceTemplate(
        $name: String!,
        $parcelId: Int!,
        $modelId: Int!,
        $activityTypeName: String!,
        $language: String!,
        $templateDefinition: String!
      ) {
        addTemplate(
          name: $name,
          parcelId: $parcelId,
          modelId: $modelId,
          activityTypeName: $activityTypeName,
          language: $language,
          templateDefinition: $templateDefinition
        ) {
          id
        }
      }
    `,
    {
      name,
      parcelId,
      modelId,
      activityTypeName,
      language,
      templateDefinition
    },
  );
  return res.addTemplate.id;
}

export async function assignActivitiesByFilter(
  graphqlClient: GraphQLClient,
  filterId: number,
  simulationDatasetId: number,
  seqId: string,
  timeRangeStart: string,
  timeRangeEnd: string
): Promise<boolean> {
  const result = await graphqlClient.request<{
    assignActivitiesByFilter: {
      success: boolean
    };
  }>(
    gql`
      mutation AssignActivitiesByFilter(
        $filterId: Int!,
        $simulationDatasetId: Int!,
        $seqId: String!,
        $timeRangeStart: String!,
        $timeRangeEnd: String!
      ) {
        assignActivitiesByFilter(
          filterId: $filterId,
          simulationDatasetId: $simulationDatasetId,
          seqId: $seqId,
          timeRangeStart: $timeRangeStart,
          timeRangeEnd: $timeRangeEnd
        ) {
          success
        }
      }
    `,
    {
      filterId,
      simulationDatasetId,
      seqId,
      timeRangeStart,
      timeRangeEnd
    },
  );

  return result.assignActivitiesByFilter.success;
}

export async function createSequenceFilter(
  graphqlClient: GraphQLClient,
  filter: ActivityLayerFilter,
  seqName: string,
  modelId: number,
): Promise<number> {
  const result = await graphqlClient.request<{
    createSequenceFilter: {
      id: number
    };
  }>(
    gql`
      mutation CreateSequenceFilter($definition: sequence_filter_insert_input!) {
        createSequenceFilter: insert_sequence_filter_one(object: $definition) {
          id
        }
      }
    `,
    {
      definition: {
        filter,
        model_id: modelId,
        name: seqName
      }
    },
  );

  return result.createSequenceFilter.id;
}

export async function expandTemplates(
  graphqlClient: GraphQLClient,
  modelId: number,
  seqIds: string[],
  simulationDatasetId: number,
): Promise<{ [seqId: string]: string }> {
  const result = await graphqlClient.request<{
    expandAllTemplates: {
      success: boolean,
      expandedSequencesBySeqId: { [seqId: string]: string };
    };
  }>(
    gql`
      mutation ExpandTemplates($modelId: Int!, $seqIds: [String!]!, $simulationDatasetId: Int!) {
        expandAllTemplates(
          modelId: $modelId,
          seqIds: $seqIds,
          simulationDatasetId: $simulationDatasetId
        ) {
          success
          expandedSequencesBySeqId
        }
      }
    `,
    {
      modelId,
      seqIds,
      simulationDatasetId,
    },
  );

  return result.expandAllTemplates.expandedSequencesBySeqId;
}
