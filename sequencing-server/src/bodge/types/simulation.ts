import type { ActivityDirectiveId } from './activity';
import type { UserId } from './app';
import type { BaseError, SimulationDatasetError } from './errors';
import type { ArgumentsMap } from './parameter';
import type { ValueSchema } from './schema';
import type { Subscription } from './subscribable';

export type PlanDataset = {
  dataset: { profiles: Profile[] };
  dataset_id: number;
  offset_from_plan_start: string;
};

export type PlanDatasetNames = {
  dataset: { profiles: { name: string }[] };
};

export type Profile = {
  dataset_id: number;
  duration: string;
  id: number;
  name: string;
  profile_segments: ProfileSegment[];
  type: {
    schema: ValueSchema;
    type: 'discrete' | 'real';
  };
};

export type ProfileSegment = {
  dataset_id: number;
  dynamics: any;
  is_gap: boolean;
  profile_id: number;
  start_offset: string;
};

/**
 * Resources are just sampled Profiles.
 */
export type Resource = {
  name: string;
  schema: ValueSchema;
  values: ResourceValue[];
};

export type ResourceRequest = {
  controller?: AbortController;
  error: string;
  loading: boolean;
  resource: Resource | null;
  simulationDatasetId: number;
  subscription?: Subscription<Resource>;
  type: 'internal' | 'external';
};

export type ResourceType = {
  name: string;
  schema: ValueSchema;
};

export type ResourceValue = {
  is_gap?: boolean;
  x: number;
  y: number | string | null;
};

export type RawSimulationEvent = {
  causal_time: string;
  real_time: string;
  topic_index: number;
  transaction_index: number;
  value: any;
};

export type SimulationEvent = {
  dense_time: string;
  id: number;
  span_id: number;
  start_offset: string;
  topic: string;
  value: string;
};

export type SimulateResponse = {
  reason: SimulationDatasetError;
  simulationDatasetId: number;
  status: 'complete' | 'failed' | 'incomplete' | 'pending';
};

export type Simulation = {
  arguments: ArgumentsMap;
  id: number;
  revision: number;
  simulation_end_time: string | null;
  simulation_start_time: string | null;
  template: SimulationTemplate | null;
};

export type SimulationDataset = {
  arguments: ArgumentsMap;
  canceled: boolean;
  dataset_id: number;
  extent: { extent: string | null } | null;
  id: number;
  plan_revision: number;
  reason: SimulationDatasetError | null;
  requested_at: string;
  requested_by: string;
  simulation_end_time: string | null;
  simulation_revision: number;
  simulation_start_time: string | null;
  status: 'failed' | 'incomplete' | 'pending' | 'success';
};

export type SimulationDatasetSlim = Pick<SimulationDataset, 'canceled' | 'id' | 'status'>;

export type SimulationInitialUpdateInput = {
  arguments: ArgumentsMap;
  simulation_end_time: string | null;
  simulation_start_time: string | null;
  simulation_template_id: number | null;
};

export type SimulationTemplate = {
  arguments: ArgumentsMap;
  description: string;
  id: number;
  owner: UserId;
};

export type SimulationTemplateInsertInput = {
  arguments: ArgumentsMap;
  description: string;
  model_id: number;
};

export type SimulationTemplateSetInput = Partial<SimulationTemplateInsertInput>;

export type SimulationDatasetReason = {
  errors: {
    [activityId: string]: BaseError;
  };
  success: boolean;
};

export type SpanAttributes = {
  arguments: ArgumentsMap;
  computedAttributes: ArgumentsMap;
  directiveId?: number;
};

export type SpanId = number;

export type SpansMap = Record<SpanId, Span>;

export type SpanIdToDirectiveIdMap = Record<SpanId, ActivityDirectiveId>;

export type DirectiveIdToSpanIdMap = Record<ActivityDirectiveId, SpanId>;

export type SpanIdToChildIdsMap = Record<SpanId, SpanId[]>;

export type SpanUtilityMaps = {
  directiveIdToSpanIdMap: DirectiveIdToSpanIdMap;
  spanIdToChildIdsMap: SpanIdToChildIdsMap;
  spanIdToDirectiveIdMap: SpanIdToDirectiveIdMap;
};

export type SpanDB = {
  attributes: SpanAttributes;
  dataset_id: number;
  duration: string;
  durationMs: number;
  endMs: number;
  parent_id: number | null;
  span_id: SpanId;
  startMs: number;
  start_offset: string;
  type: string;
};

export type Span = SpanDB & {
  durationMs: number;
  endMs: number;
  startMs: number;
};

export type Topic = {
  name: string;
  topic_index: number;
  value_schema: ValueSchema;
};
