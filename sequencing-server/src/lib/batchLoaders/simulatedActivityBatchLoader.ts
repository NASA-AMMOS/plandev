import type { BatchLoader, InferredDataloader } from './index.js';
import { gql, GraphQLClient } from 'graphql-request';
import { ErrorWithStatusCode } from '../../utils/ErrorWithStatusCode.js';
import parse from 'postgres-interval';
import { GraphQLActivitySchema, Schema, SchemaTypes } from './activitySchemaBatchLoader.js';
import type { activitySchemaBatchLoader } from './activitySchemaBatchLoader.js';
import { assertUnreachable } from '../../utils/assertions.js';

export const simulatedActivitiesBatchLoader: BatchLoader<
  { simulationDatasetId: number },
  SimulatedActivity[],
  { graphqlClient: GraphQLClient; activitySchemaDataLoader: InferredDataloader<typeof activitySchemaBatchLoader> }
> = opts => async keys => {
  const result = await opts.graphqlClient.batchRequests<
    {
      data: {
        simulation_dataset: {
          id: number;
          simulation: {
            plan: {
              id: number;
              model_id: number;
            };
          };
          simulation_start_time: string;
          dataset: { spans: GQLSpan[] };
        };
      };
    }[]
  >(
    keys.map(key => ({
      document: gql`
        query ($simulationDatasetId: Int!) {
          simulation_dataset: simulation_dataset_by_pk(id: $simulationDatasetId) {
            id
            simulation {
              plan {
                model_id
                id
              }
            }
            simulation_start_time
            dataset {
              spans {
                span_id
                attributes
                start_offset
                duration
                activity_type_name: type
              }
            }
          }
        }
      `,
      variables: {
        simulationDatasetId: key.simulationDatasetId,
      },
    })),
  );

  return Promise.all(
    keys.map(async ({ simulationDatasetId }) => {
      const simulation_dataset = result.find(res => res.data.simulation_dataset.id === simulationDatasetId)?.data
        .simulation_dataset;
      if (simulation_dataset === undefined) {
        return new ErrorWithStatusCode(`No simulation_dataset with id: ${simulationDatasetId}`, 404);
      }

      const spans = simulation_dataset.dataset.spans;

      const simulatedActivities: GraphQLSimulatedActivityInstance[] = spans.map(span => {
        return {
          id: span.span_id,
          simulation_dataset_id: simulation_dataset.id,
          plan_id: simulation_dataset.simulation.plan.id,
          model_id: simulation_dataset.simulation.plan.model_id,
          attributes: span.attributes,
          duration: span.duration,
          start_offset: span.start_offset,
          simulation_start_time: simulation_dataset.simulation_start_time,
          activity_type_name: span.activity_type_name,
        };
      });
      return Promise.all(
        simulatedActivities.map(async simulatedActivity =>
          mapGraphQLActivityInstance(
            simulatedActivity,
            await opts.activitySchemaDataLoader.load({
              missionModelId: simulation_dataset.simulation.plan.model_id,
              activityTypeName: simulatedActivity.activity_type_name,
            }),
          ),
        ),
      );
    }),
  );
};

export const simulatedActivityInstanceBySimulatedActivityIdBatchLoader: BatchLoader<
  { simulationDatasetId: number; simulatedActivityId: number },
  SimulatedActivity,
  { graphqlClient: GraphQLClient; activitySchemaDataLoader: InferredDataloader<typeof activitySchemaBatchLoader> }
> = opts => async keys => {
  const result = await opts.graphqlClient.batchRequests<
    {
      data: {
        simulation_dataset: {
          id: number;
          simulation_start_time: string;
          simulation: {
            plan: {
              id: number;
              model_id: number;
            };
          };
          dataset: { spans: GQLSpan[] };
        };
      };
    }[]
  >(
    keys.map(key => ({
      document: gql`
        query ($simulationDatasetId: Int!, $simulatedActivityId: Int!) {
          simulation_dataset: simulation_dataset_by_pk(id: $simulationDatasetId) {
            id
            simulation_start_time
            simulation {
              plan {
                id
                model_id
              }
            }
            dataset {
              spans: spans(where: { span_id: { _eq: $simulatedActivityId } }) {
                span_id
                attributes
                start_offset
                duration
                activity_type_name: type
              }
            }
          }
        }
      `,
      variables: {
        simulationDatasetId: key.simulationDatasetId,
        simulatedActivityId: key.simulatedActivityId,
      },
    })),
  );

  return Promise.all(
    keys.map(async ({ simulationDatasetId, simulatedActivityId }) => {
      const simulation_dataset = result.find(res =>
        res.data?.simulation_dataset?.dataset?.spans?.some(span => span.span_id === simulatedActivityId),
      )?.data.simulation_dataset;
      if (simulation_dataset === undefined) {
        return new ErrorWithStatusCode(`No simulation_dataset with id: ${simulationDatasetId}`, 404);
      }

      const spans = simulation_dataset?.dataset.spans;
      if (spans === undefined || spans.length === 0 || spans[0] === undefined) {
        return new ErrorWithStatusCode(
          `No simulation_dataset with id: ${simulationDatasetId} and simulated activity id: ${simulatedActivityId}`,
          404,
        );
      }

      if(spans.length > 1) {
        return new ErrorWithStatusCode(
            `Too many spans with simulated activity id ${simulatedActivityId} found for simulation_dataset with id ${simulationDatasetId}`,
            404,
        );
      }

      const span = spans[0];
      const simulatedActivity: GraphQLSimulatedActivityInstance = {
        id: span.span_id,
        simulation_dataset_id: simulation_dataset.id,
        plan_id: simulation_dataset.simulation.plan.id,
        model_id: simulation_dataset.simulation.plan.model_id,
        attributes: span.attributes,
        duration: span.duration,
        start_offset: span.start_offset,
        simulation_start_time: simulation_dataset.simulation_start_time,
        activity_type_name: span.activity_type_name,
      };
      return mapGraphQLActivityInstance(
        simulatedActivity,
        await opts.activitySchemaDataLoader.load({
          missionModelId: simulation_dataset.simulation.plan.model_id,
          activityTypeName: simulatedActivity.activity_type_name,
        }),
      );
    }),
  );
};

export const simulatedActivityInstanceBySeqIdBatchLoader: BatchLoader<
  { simulationDatasetId: number; seqId: number },
  SimulatedActivity[],
  { graphqlClient: GraphQLClient; activitySchemaDataLoader: InferredDataloader<typeof activitySchemaBatchLoader> }
> = opts => async keys => {
  const result = await opts.graphqlClient.batchRequests<
    {
      data: {
        simulation_dataset: {
          id: number;
          simulation_start_time: string;
          simulation: {
            plan: {
              id: number;
              model_id: number;
            };
          }
        };
        sequence_to_simulated_activity: { spans: GQLSpan }[];
      };
    }[]
  >(
    keys.map(key => ({
      document: gql`
        query ($simulationDatasetId: Int!, $seqId: String!) {
          simulation_dataset: simulation_dataset_by_pk(id: $simulationDatasetId) {
            id
            simulation_start_time
            simulation {
              plan {
                id
                model_id
              }
            }
          }
          sequence_to_simulated_activity(where: {_and: [{simulation_dataset_id: {_eq: $simulationDatasetId}},  {seq_id: {_eq: $seqId}}]}) {
            spans {
              span_id
              attributes
              start_offset
              duration
              activity_type_name: type
            }
          }
        }
      `,
      variables: {
        simulationDatasetId: key.simulationDatasetId,
        seqId: key.seqId,
      },
    })),
  );

  return Promise.all(
    keys.map(async ({ simulationDatasetId, seqId }) => {
      const simulation_dataset = result.find(res => res.data.simulation_dataset.id === simulationDatasetId)?.data
        .simulation_dataset;
      if (simulation_dataset === undefined) {
        return new ErrorWithStatusCode(`No simulation_dataset with id: ${simulationDatasetId}`, 404);
      }

      const filteredSpans = result.find(res => res.data.simulation_dataset.id === simulationDatasetId)?.data.sequence_to_simulated_activity;
      if (filteredSpans === undefined || filteredSpans.length === 0 || filteredSpans[0] === undefined) {
        return new ErrorWithStatusCode(
          `No simulated, filtered activities with id: ${simulationDatasetId} and seqId: ${seqId}`,
          404,
        );
      }

      const simulatedActivities: GraphQLSimulatedActivityInstance[] = filteredSpans.map(span => {
        const simulatedActivity = span.spans
        return {
          id: simulatedActivity.span_id,
          simulation_dataset_id: simulation_dataset.id,
          plan_id: simulation_dataset.simulation.plan.id,
          model_id: simulation_dataset.simulation.plan.model_id,
          attributes: simulatedActivity.attributes,
          duration: simulatedActivity.duration,
          start_offset: simulatedActivity.start_offset,
          simulation_start_time: simulation_dataset.simulation_start_time,
          activity_type_name: simulatedActivity.activity_type_name,
        };
      });

      return Promise.all(
        simulatedActivities.map(async simulatedActivity =>
          mapGraphQLActivityInstance(
            simulatedActivity,
            await opts.activitySchemaDataLoader.load({
              missionModelId: simulation_dataset.simulation.plan.model_id,
              activityTypeName: simulatedActivity.activity_type_name,
            }),
          ),
        ),
      );
    }),
  );
};

export interface SimulatedActivityAttributes<
  ActivityArguments extends Record<string, unknown> = Record<string, unknown>,
  ActivityComputedAttributes extends Record<string, unknown> = Record<string, unknown>,
> {
  arguments: ActivityArguments;
  directiveId: number | undefined;
  computed: ActivityComputedAttributes | undefined;
}
export interface GraphQLSimulatedActivityAttributes<
  ActivityArguments extends Record<string, unknown> = Record<string, unknown>,
  ActivityComputedAttributes extends Record<string, unknown> = Record<string, unknown>,
> {
  arguments: ActivityArguments;
  directiveId: number | undefined;
  computedAttributes: ActivityComputedAttributes | undefined;
}

export interface SimulatedActivity<
  ActivityArguments extends Record<string, unknown> = Record<string, unknown>,
  ActivityComputedAttributes extends Record<string, unknown> = Record<string, unknown>,
> {
  id: number;
  simulationDatasetId: number;
  simulationDataset: {
    simulation: {
      planId: number;
    };
  };
  attributes: SimulatedActivityAttributes<ActivityArguments, ActivityComputedAttributes>;
  duration: Temporal.Duration | null;
  startOffset: Temporal.Duration;
  startTime: Temporal.Instant;
  endTime: Temporal.Instant | null;
  activityTypeName: string;
}

export interface GQLSpan<
  ActivityArguments extends Record<string, unknown> = Record<string, unknown>,
  ActivityComputedAttributes extends Record<string, unknown> = Record<string, unknown>,
> {
  span_id: number;
  attributes: GraphQLSimulatedActivityAttributes<ActivityArguments, ActivityComputedAttributes>;
  start_offset: string;
  duration: string;
  activity_type_name: string;
}

export interface GraphQLSimulatedActivityInstance<
  ActivityArguments extends Record<string, unknown> = Record<string, unknown>,
  ActivityComputedAttributes extends Record<string, unknown> = Record<string, unknown>,
> {
  id: number;
  simulation_dataset_id: number;
  plan_id: number;
  model_id: number;
  attributes: GraphQLSimulatedActivityAttributes<ActivityArguments, ActivityComputedAttributes>;
  duration: string;
  start_offset: string;
  simulation_start_time: string;
  activity_type_name: string;
}

export function mapGraphQLActivityInstance(
  activityInstance: GraphQLSimulatedActivityInstance<any, any>,
  activitySchema: GraphQLActivitySchema,
): SimulatedActivity {
  const duration = activityInstance.duration
    ? Temporal.Duration.from(parse(activityInstance.duration).toISOString())
    : null;
  const startOffset: Temporal.Duration = Temporal.Duration.from(parse(activityInstance.start_offset).toISOString());
  const startTime: Temporal.Instant = Temporal.Instant.from(activityInstance.simulation_start_time)
    .toZonedDateTimeISO('UTC')
    .add(startOffset)
    .toInstant();
  const endTime = duration ? startTime.toZonedDateTimeISO('UTC').add(duration).toInstant() : null;

  return {
    simulationDataset: {
      simulation: {
        planId: activityInstance.plan_id,
      },
    },
    id: activityInstance.id,
    duration,
    startOffset,
    startTime,
    endTime,
    simulationDatasetId: activityInstance.simulation_dataset_id,
    activityTypeName: activityInstance.activity_type_name,
    attributes: {
      arguments: Object.entries(activityInstance.attributes.arguments).reduce((acc, [key, value]) => {
        const param = activitySchema.parameters[key];
        if (param !== undefined) {
          acc[key] = convertType(value, param.schema);
        }
        return acc;
      }, {} as Record<string, any>),
      directiveId: activityInstance.attributes.directiveId,
      computed: activityInstance.attributes.computedAttributes
        ? convertType(activityInstance.attributes.computedAttributes, activitySchema.computed_attributes_value_schema)
        : null,
    },
  };
}

function convertType(value: any, schema: Schema): any {
  switch (schema.type) {
    case SchemaTypes.Int:
      if (value === null) {
        return value;
      }
      if (value > Number.MAX_SAFE_INTEGER || value < Number.MIN_SAFE_INTEGER) {
        return value.toString();
      }
      return parseInt(value, 10);
    case SchemaTypes.Real:
      return value;
    case SchemaTypes.Duration:
      if (value !== null) {
        return Temporal.Duration.from({ microseconds: value });
      }
      return value;
    case SchemaTypes.Boolean:
      return value;
    case SchemaTypes.Path:
      return value;
    case SchemaTypes.String:
      return value;
    case SchemaTypes.Series:
      if (value === null) {
        return value;
      }
      return value.map((value: any) => convertType(value, schema.items));
    case SchemaTypes.Struct:
      if (value === null) {
        return value;
      }
      const struct: { [attributeName: string]: any } = {};
      for (const [attributeKey, attributeSchema] of Object.entries(schema.items)) {
        struct[attributeKey] = convertType(value[attributeKey], attributeSchema);
      }
      return struct;
    case SchemaTypes.Variant:
      if (value === null || (schema.variants.length === 1 && schema.variants[0]?.key === 'VOID')) {
        return null;
      }
      return value;
    default:
      assertUnreachable(schema);
  }
}
