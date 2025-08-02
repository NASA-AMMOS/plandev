import { gql, GraphQLClient } from 'graphql-request';
import type { ActivityLayerFilter } from '../../src/lib/filters/types';

export async function insertExpansion(
  graphqlClient: GraphQLClient,
  activityTypeName: string,
  expansionLogic: string,
  parcelId: number,
): Promise<number> {
  const res = await graphqlClient.request<{
    addCommandExpansionTypeScript: { id: number };
  }>(
    gql`
      mutation AddCommandExpansion($activityTypeName: String!, $expansionLogic: String!, $parcelId: Int!) {
        addCommandExpansionTypeScript(
          activityTypeName: $activityTypeName
          expansionLogic: $expansionLogic
          parcelId: $parcelId
        ) {
          id
        }
      }
    `,
    {
      activityTypeName,
      expansionLogic,
      parcelId,
    },
  );
  return res.addCommandExpansionTypeScript.id;
}

export async function getExpansion(
  graphqlClient: GraphQLClient,
  expansionId: number,
): Promise<{
  id: number;
  expansion_logic: string;
  name: string;
  parcel_id: number;
}> {
  const res = await graphqlClient.request<{
    expansion_rule_by_pk: any;
  }>(
    gql`
      query GetExpansion($expansionId: Int!) {
        expansion_rule_by_pk(id: $expansionId) {
          id
          expansion_logic
          name
          parcel_id
        }
      }
    `,
    {
      expansionId,
    },
  );
  return res.expansion_rule_by_pk;
}

export async function removeExpansion(graphqlClient: GraphQLClient, expansionId: number): Promise<void> {
  return graphqlClient.request(
    gql`
      mutation DeleteExpansionRule($expansionId: Int!) {
        delete_expansion_rule_by_pk(id: $expansionId) {
          id
        }
      }
    `,
    {
      expansionId,
    },
  );
}

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

export async function insertExpansionSet(
  graphqlClient: GraphQLClient,
  parcelId: number,
  missionModelId: number,
  expansionIds: number[],
  description?: string,
  name?: string,
): Promise<number> {
  const res = await graphqlClient.request<{
    createExpansionSet: { id: number };
  }>(
    gql`
      mutation AddExpansionSet(
        $parcelId: Int!
        $missionModelId: Int!
        $expansionIds: [Int!]!
        $description: String
        $name: String
      ) {
        createExpansionSet(
          parcelId: $parcelId
          missionModelId: $missionModelId
          expansionIds: $expansionIds
          description: $description
          name: $name
        ) {
          id
        }
      }
    `,
    {
      parcelId,
      missionModelId,
      expansionIds,
      description,
      name,
    },
  );
  return res.createExpansionSet.id;
}

export async function getExpansionSet(
  graphqlClient: GraphQLClient,
  expansionSetId: number,
): Promise<{
  id: number;
  name: string;
  description: string;
  mission_model_id: number;
  parcel_id: number;
  expansion_rules: { id: number }[];
} | null> {
  const res = await graphqlClient.request(
    gql`
      query GetExpansionSet($expansionSetId: Int!) {
        expansion_set_by_pk(id: $expansionSetId) {
          id
          name
          description
          mission_model_id
          parcel_id
          expansion_rules {
            id
          }
        }
      }
    `,
    {
      expansionSetId,
    },
  );

  return res.expansion_set_by_pk;
}

export async function removeExpansionSet(graphqlClient: GraphQLClient, expansionSetId: number): Promise<void> {
  return graphqlClient.request(
    gql`
      mutation DeleteExpansionSet($expansionSetId: Int!) {
        delete_expansion_set_by_pk(id: $expansionSetId) {
          id
        }
      }
    `,
    {
      expansionSetId,
    },
  );
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

export async function expandLegacy(
  graphqlClient: GraphQLClient,
  expansionSetId: number,
  simulationDatasetId: number,
): Promise<number> {
  const result = await graphqlClient.request<{
    expandAllActivities: {
      id: number;
    };
  }>(
    gql`
      mutation ExpandLegacy($expansionSetId: Int!, $simulationDatasetId: Int!) {
        expandAllActivities(expansionSetId: $expansionSetId, simulationDatasetId: $simulationDatasetId) {
          id
        }
      }
    `,
    {
      expansionSetId,
      simulationDatasetId,
    },
  );

  return result.expandAllActivities.id;
}

export async function getExpandedSequence(
  graphqlClient: GraphQLClient,
  expansionRunId: number,
  seqId: string,
): Promise<{
  expandedSequence: Sequence;
}> {
  const result = await graphqlClient.request<{
    expanded_sequences: [
      {
        expanded_sequence: Sequence;
      },
    ];
  }>(
    gql`
      query GetExpandedSequence($expansionRunId: Int!, $seqId: String!) {
        expanded_sequences(where: { expansion_run_id: { _eq: $expansionRunId }, seq_id: { _eq: $seqId } }) {
          expanded_sequence
        }
      }
    `,
    {
      expansionRunId,
      seqId,
    },
  );

  return {
    expandedSequence: result.expanded_sequences[0].expanded_sequence,
  };
}

export async function removeExpansionRun(graphqlClient: GraphQLClient, expansionRunId: number): Promise<void> {
  await graphqlClient.request<{
    delete_expansion_run_by_pk: {
      id: number;
    };
  }>(
    gql`
      mutation deleteExpansionRun($expansionRunId: Int!) {
        delete_expansion_run_by_pk(id: $expansionRunId) {
          id
        }
      }
    `,
    {
      expansionRunId,
    },
  );

  return;
}
