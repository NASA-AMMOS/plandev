import { ErrorWithStatusCode } from '../../utils/ErrorWithStatusCode.js';
import type { BatchLoader } from './index.js';
import { gql, GraphQLClient } from 'graphql-request';

export const activitySchemaBatchLoader: BatchLoader<
  { missionModelId: number; activityTypeName: string },
  GraphQLActivitySchema,
  { graphqlClient: GraphQLClient }
> = opts => async keys => {
  const { activity_type } = await opts.graphqlClient.request<{
    activity_type: (GraphQLActivitySchema & { model_id: number })[];
  }>(gql`
   query GetActivitySchema {
      activity_type(where: {
        _or: [
          ${keys
            .map(
              key => `{
              model_id: { _eq: ${key.missionModelId} },
              name: { _eq: "${key.activityTypeName}" }
             }`,
            )
            .join(', ')}
        ]
      }) {
        name
        parameters
        subsystem_tag {
          id
        }
        computed_attributes_value_schema
        model_id
      }
    }
  `);

  return Promise.all(
    keys.map(async ({ missionModelId, activityTypeName }) => {
      const activitySchema = activity_type.find(
        activitySchema =>
          activitySchema.model_id.toString() === missionModelId.toString() && activitySchema.name === activityTypeName,
      );
      if (activitySchema === undefined) {
        return new ErrorWithStatusCode(
          `No activity with name: ${activityTypeName} in mission model with id: ${missionModelId}`,
          404,
        );
      }
      return activitySchema;
    }),
  );
};

export enum SchemaTypes {
  Int = 'int',
  Real = 'real',
  Duration = 'duration',
  Boolean = 'boolean',
  String = 'string',
  Series = 'series',
  Struct = 'struct',
  Variant = 'variant',
  Path = 'path',
}

export type Schema =
  | IntSchema
  | RealSchema
  | DurationSchema
  | BooleanSchema
  | StringSchema
  | SeriesSchema
  | StructSchema
  | VariantSchema
  | PathSchema;

interface BaseSchema<T extends SchemaTypes | unknown> {
  type: T;
}

type StringSchema = BaseSchema<SchemaTypes.String>;
type BooleanSchema = BaseSchema<SchemaTypes.Boolean>;
type IntSchema = BaseSchema<SchemaTypes.Int>;
type RealSchema = BaseSchema<SchemaTypes.Real>;
type DurationSchema = BaseSchema<SchemaTypes.Duration>;
type PathSchema = BaseSchema<SchemaTypes.Path>;

interface SeriesSchema extends BaseSchema<SchemaTypes.Series> {
  items: Schema;
}

interface StructSchema extends BaseSchema<SchemaTypes.Struct> {
  items: {
    [key: string]: Schema;
  };
}

interface VariantSchema extends BaseSchema<SchemaTypes.Variant> {
  variants: {
    key: string;
    label: string;
  }[];
}

interface GraphQLActivityParameter {
  order: number;
  schema: Schema;
}

export interface GraphQLActivitySchema {
  subsystem_tag?: { id: number } | null;
  name: string;
  parameters: { [key: string]: GraphQLActivityParameter };
  requiredParameters: string[];
  computed_attributes_value_schema: Schema;
}
