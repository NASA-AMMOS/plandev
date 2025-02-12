import type { ActivityDeletionAction } from '../utilities/activities';
import type { ActivityMetadata } from './activity-metadata';
import type { PartialWith, UserId } from './app';
import type { ActivityDirectiveValidationFailures } from './errors';
import type { ExpansionRuleSlim } from './expansion';
import type { ArgumentsMap, ParametersMap } from './parameter';
import type { ValueSchema } from './schema';
import type { Tag } from './tags';

export type ActivityType = {
  computed_attributes_value_schema: ValueSchema;
  name: string;
  parameters: ParametersMap;
  required_parameters: string[];
  subsystem_tag?: Tag;
};

export type ActivityTypeExpansionRules = {
  expansion_rules: ExpansionRuleSlim[];
  name: ActivityType['name'];
};

export type ActivityDirectiveId = number;
export type ActivityPresetId = number;

export type ActivityDirectivesMap = Record<ActivityDirectiveId, ActivityDirective>;
export type AnchoredActivityDirectivesMap = Record<ActivityDirectiveId, ActivityDirective[]>;

export type ActivityDirectiveDeletionMap = Record<ActivityDirectiveId, ActivityDeletionAction>;

export type ActivityDirectiveDB = {
  anchor_id: number | null;
  anchor_validations?: AnchorValidationStatus;
  anchored_to_start: boolean;
  applied_preset?: AppliedPreset | null;
  arguments: ArgumentsMap;
  created_at: string;
  created_by: string;
  id: ActivityDirectiveId;
  last_modified_arguments_at: string;
  last_modified_at: string;
  last_modified_by?: string | null;
  metadata: ActivityMetadata;
  name: string;
  plan_id: number;
  source_scheduling_goal_id: number | null;
  start_offset: string;
  tags: { tag: Tag }[];
  type: string;
};

export type ActivityDirective = ActivityDirectiveDB & {
  start_time_ms: number | null;
};

export type ActivityDirectiveInsertInput = {
  anchor_id: number | null;
  anchored_to_start: boolean;
  arguments: ArgumentsMap;
  metadata: ActivityMetadata;
  name: string;
  plan_id: number;
  start_offset: string;
  type: string;
};

export type ActivityDirectiveSetInput = Partial<ActivityDirectiveInsertInput>;

export type ActivityDirectiveRevision = {
  anchor_id: number | null;
  anchored_to_start: boolean;
  arguments: ArgumentsMap;
  changed_at: string;
  changed_by: string;
  metadata: ActivityMetadata;
  name: string;
  revision: number;
  start_offset: string;
};

export type AppliedPreset = {
  activity_id: ActivityDirectiveId;
  plan_id: number;
  preset_applied: ActivityPreset;
  preset_id: ActivityPresetId;
};

export type ActivityPreset = {
  arguments: ArgumentsMap;
  associated_activity_type: string;
  id: ActivityPresetId;
  model_id: number;
  name: string;
  owner: UserId;
};

export type ActivityPresetInsertInput = Pick<
  ActivityPreset,
  'arguments' | 'associated_activity_type' | 'model_id' | 'name'
>;

export type ActivityPresetSetInput = PartialWith<ActivityPreset, 'id' | 'owner'>;

export type AnchorValidationStatus = {
  activity_id: ActivityDirectiveId;
  plan_id: number;
  reason_invalid: string;
};

export interface ActivityDirectiveValidationStatus {
  directive_id: number;
  plan_id: number;
  status: 'complete' | 'pending';
  validations: ActivityDirectiveValidationSuccess | ActivityDirectiveValidationFailures;
}

export interface ActivityDirectiveValidationSuccess {
  success: true;
}

export type PlanSnapshotActivity = Omit<ActivityDirective, 'anchor_validations' | 'applied_preset' | 'plan_id'> & {
  snapshot_id: number;
};

export type PlanSnapshotActivityDB = Omit<ActivityDirectiveDB, 'anchor_validations' | 'applied_preset' | 'plan_id'> & {
  snapshot_id: number;
};
