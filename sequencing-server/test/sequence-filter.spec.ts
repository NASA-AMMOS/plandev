import { jest } from '@jest/globals';
import type { Request, Response } from 'express';
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

const query = jest.fn<(...args: unknown[]) => Promise<{ rows: unknown[] }>>();
jest.unstable_mockModule('../src/app.js', () => ({ db: { query } }));
jest.unstable_mockModule('../src/utils/logger.js', () => ({ default: () => ({ info: jest.fn() }) }));
const { commandExpansionRouter } = await import('../src/routes/command-expansion.js');
const handler = commandExpansionRouter.stack.find(layer => layer.route?.path === '/assign-activities-by-filter')?.route
  ?.stack[0]?.handle;

it('successfully applies a subsystem filter with no matching activities', async () => {
  query.mockReset().mockResolvedValue({ rows: [] });
  const req = {
    body: {
      input: {
        filterId: 1,
        simulationDatasetId: 1,
        seqId: 'Subsystem sequence',
        timeRangeStart: '2026-001T00:00:00Z',
        timeRangeEnd: '2026-002T00:00:00Z',
      },
    },
  } as Request;
  const res = {
    locals: {
      context: {
        simulatedActivitiesDataLoader: { load: async () => activities },
        sequenceFilterDataLoader: {
          load: async () => ({
            filter: {
              dynamic_type_filters: [{ field: 'Subsystem', id: 1, operator: 'includes', value: [999] }],
            },
          }),
        },
      },
    },
    status: jest.fn(),
    json: jest.fn(),
  };
  res.status.mockReturnValue(res);
  const next = jest.fn();
  expect(handler).toBeDefined();
  await handler!(req, res as unknown as Response, next);
  expect(query).toHaveBeenCalledWith(expect.any(String), [[], 1, 'Subsystem sequence']);
  expect(res.status).toHaveBeenCalledWith(200);
  expect(res.json).toHaveBeenCalledWith({ success: true });
  expect(next).toHaveBeenCalled();
});
