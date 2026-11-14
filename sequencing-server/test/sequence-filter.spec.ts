import { jest } from '@jest/globals';
import type { GraphQLClient } from 'graphql-request';
import { activitySchemaBatchLoader, SchemaTypes } from '../src/lib/batchLoaders/activitySchemaBatchLoader.js';
import { mapGraphQLActivityInstance } from '../src/lib/batchLoaders/simulatedActivityBatchLoader.js';
import { applyActivityLayerFilter } from '../src/lib/filters/utilities.js';
import type { ActivityLayerFilter } from '../src/lib/filters/types.js';

const start = Temporal.Instant.from('2026-01-01T00:00:00Z');
const end = Temporal.Instant.from('2026-01-02T00:00:00Z');

function activity(id: number, type: string, subsystem: number | null) {
  return mapGraphQLActivityInstance(
    {
      id,
      simulation_dataset_id: 1,
      plan_id: 1,
      model_id: 1,
      activity_type_name: type,
      simulation_start_time: start.toString(),
      start_offset: '00:01:00',
      duration: '00:01:00',
      attributes: { arguments: {}, directiveId: id, computedAttributes: undefined },
    },
    {
      name: type,
      parameters: {},
      requiredParameters: [],
      computed_attributes_value_schema: { type: SchemaTypes.Struct, items: {} },
      subsystem_tag: subsystem === null ? null : { id: subsystem },
    },
  );
}

const activities = [activity(1, 'Imaging', 10), activity(2, 'Downlink', 20), activity(3, 'Other', null)];

function matches(filter: ActivityLayerFilter) {
  return applyActivityLayerFilter(filter, activities, start, end).map(a => a.id);
}

it('loads subsystem IDs with the activity schema', async () => {
  const request = jest.fn<GraphQLClient['request']>().mockResolvedValue({
    activity_type: [
      {
        model_id: 1,
        name: 'Imaging',
        subsystem_tag: { id: 10 },
      },
    ],
  });
  const schemas = await activitySchemaBatchLoader({ graphqlClient: { request } as unknown as GraphQLClient })([
    { missionModelId: 1, activityTypeName: 'Imaging' },
  ]);
  expect(request.mock.calls[0]?.[0]).toMatch(/subsystem_tag\s*\{\s*id\s*\}/);
  expect(schemas[0]).toMatchObject({ subsystem_tag: { id: 10 } });
});

it.each([
  ['includes', [10], [1]],
  ['does_not_include', [10], [2, 3]],
] as const)('evaluates subsystem %s %j', (operator, value, expected) => {
  expect(matches({ dynamic_type_filters: [{ field: 'Subsystem', id: 0, operator, value: [...value] }] })).toEqual(
    expected,
  );
});
