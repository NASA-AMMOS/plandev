import { jest } from '@jest/globals';
import type { Request, Response } from 'express';
import { SchemaTypes } from '../../src/lib/batchLoaders/activitySchemaBatchLoader.js';
import { mapGraphQLActivityInstance } from '../../src/lib/batchLoaders/simulatedActivityBatchLoader.js';

const query = jest.fn<(...args: unknown[]) => Promise<{ rows: unknown[] }>>();
jest.unstable_mockModule('../../src/app.js', () => ({ db: { query } }));
jest.unstable_mockModule('../../src/utils/logger.js', () => ({ default: () => ({ info: jest.fn() }) }));
const { commandExpansionRouter } = await import('../../src/routes/command-expansion.js');
const handler = commandExpansionRouter.stack.find(layer => layer.route?.path === '/assign-activities-by-filter')?.route
  ?.stack[0]?.handle;

it.each([true, false])('applies a subsystem filter when activities match: %s', async matches => {
  query.mockReset().mockResolvedValue({ rows: [] });
  const activity = mapGraphQLActivityInstance(
    {
      id: 7,
      simulation_dataset_id: 1,
      plan_id: 1,
      model_id: 1,
      activity_type_name: 'Imaging',
      simulation_start_time: '2026-01-01T00:00:00Z',
      start_offset: '00:01:00',
      duration: '00:01:00',
      attributes: { arguments: {}, directiveId: 1, computedAttributes: undefined },
    },
    {
      name: 'Imaging',
      parameters: {},
      requiredParameters: [],
      subsystem_tag: { id: 10 },
      computed_attributes_value_schema: { type: SchemaTypes.Struct, items: {} },
    },
  );
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
        simulatedActivitiesDataLoader: { load: async () => [activity] },
        sequenceFilterDataLoader: {
          load: async () => ({
            filter: {
              dynamic_type_filters: [{ field: 'Subsystem', id: 1, operator: 'includes', value: [matches ? 10 : 20] }],
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
  expect(query).toHaveBeenCalledWith(expect.not.stringContaining('array_fill'), [
    matches ? [7] : [],
    1,
    'Subsystem sequence',
  ]);
  expect(res.status).toHaveBeenCalledWith(200);
  expect(res.json).toHaveBeenCalledWith({ success: true });
  expect(next).toHaveBeenCalled();
});
