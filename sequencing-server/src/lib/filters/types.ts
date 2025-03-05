import type { ActivityLayerFilterField, FilterOperator } from "./enums";

export type DynamicFilterDataType = ValueSchema['type'] | 'tag';

export type ActivityLayerFilter = {
    dynamic_type_filters?: ActivityLayerDynamicFilter<Pick<typeof ActivityLayerFilterField, 'Type' | 'Subsystem'>>[];
    other_filters?: ActivityLayerDynamicFilter<
        Pick<typeof ActivityLayerFilterField, 'Tags' | 'Parameter' | 'SchedulingGoalId' | 'Name'>
    >[];
    static_types?: string[];
    type_subfilters?: Record<
        string,
        ActivityLayerDynamicFilter<
            Pick<typeof ActivityLayerFilterField, 'Tags' | 'Parameter' | 'SchedulingGoalId' | 'Name'>
        >[]
    >;
};
export type ExternalEventLayerFilter = {
    event_types: string[];
};

export type ActivityLayerDynamicFilter<T> = {
    field: keyof T;
    id: number;
    operator: keyof typeof FilterOperator;
    subfield?: { name: string; type: DynamicFilterDataType };
    value: string | string[] | number | number[] | boolean;
};

export type ActivityLayerFilterSubfield = { name: string; type: DynamicFilterDataType };
export type ActivityLayerFilterSubfieldSchema = ActivityLayerFilterSubfield & {
    activityTypes: string[];
    label: string;
    unit?: string;
    values?: string[];
};



// from types/activity.ts
export type ActivityType = {
    computed_attributes_value_schema: ValueSchema;
    name: string;
    parameters: ParametersMap;
    required_parameters: string[];
    subsystem_tag?: Tag | null;
};

export type ActivityDirectiveId = number;
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
export type AppliedPreset = {
    activity_id: ActivityDirectiveId;
    plan_id: number;
    preset_applied: ActivityPreset;
    preset_id: ActivityPresetId;
};
export type AnchorValidationStatus = {
    activity_id: ActivityDirectiveId;
    plan_id: number;
    reason_invalid: string;
};
export type ActivityPresetId = number;
export type ActivityPreset = {
    arguments: ArgumentsMap;
    associated_activity_type: string;
    id: ActivityPresetId;
    model_id: number;
    name: string;
    owner: UserId;
};
export type ActivityDirective = ActivityDirectiveDB & {
    start_time_ms: number | null;
};

// from types/activity-metadata.ts
export type ActivityMetadataValueBoolean = boolean;

export type ActivityMetadataValueEnum = number | string;

export type ActivityMetadataValueEnumMultiselect = (number | string)[];

export type ActivityMetadataValueLongString = string;

export type ActivityMetadataValueNumber = number;

export type ActivityMetadataValueString = string;

export type ActivityMetadataValue =
    | ActivityMetadataValueBoolean
    | ActivityMetadataValueEnum
    | ActivityMetadataValueEnumMultiselect
    | ActivityMetadataValueLongString
    | ActivityMetadataValueNumber
    | ActivityMetadataValueString;

export type ActivityMetadataKey = string;

export type ActivityMetadata = Record<ActivityMetadataKey, ActivityMetadataValue>;

// from types/parameter.ts
export type ParameterName = string;
export type ParametersMap = Record<ParameterName, Parameter>;
export type ArgumentsMap = Record<ParameterName, Argument>;
export type Argument = any;
export type DefaultEffectiveArgumentsMap = Record<string, ArgumentsMap>;

// from types/tags.ts
export type Tag = {
    color: string | null;
    created_at: string;
    id: number;
    name: string;
    owner: UserId;
};

// from types/app.ts
export type UserId = string | null;

// from types/schema.ts
type ValueSchemaMetadata = {
    metadata?: {
        unit?: {
            value: string;
        };
    } & Record<string, any>;
};

export type ValueSchemaBoolean = {
    type: 'boolean';
} & ValueSchemaMetadata;

export type ValueSchemaDuration = {
    type: 'duration';
} & ValueSchemaMetadata;

export type ValueSchemaInt = {
    type: 'int';
} & ValueSchemaMetadata;

export type ValueSchemaPath = {
    type: 'path';
} & ValueSchemaMetadata;

export type ValueSchemaReal = {
    type: 'real';
} & ValueSchemaMetadata;

export type ValueSchemaSeries = {
    items: ValueSchema;
    type: 'series';
} & ValueSchemaMetadata;

export type ValueSchemaString = {
    type: 'string';
} & ValueSchemaMetadata;

export type ValueSchemaStruct = {
    items: Record<string, ValueSchema>;
    type: 'struct';
} & ValueSchemaMetadata;

export type ValueSchemaVariant = {
    type: 'variant';
    variants: Variant[];
} & ValueSchemaMetadata;

export type ValueSchema =
    | ValueSchemaBoolean
    | ValueSchemaDuration
    | ValueSchemaInt
    | ValueSchemaPath
    | ValueSchemaReal
    | ValueSchemaSeries
    | ValueSchemaString
    | ValueSchemaStruct
    | ValueSchemaVariant;

export type Variant = {
    key: string;
    label: string;
};

// from types/simulation.ts

export type SpanAttributes = {
    arguments: ArgumentsMap;
    computedAttributes: ArgumentsMap;
    directiveId?: number;
};
export type SpanId = number;

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

// from types/sequencing.ts
export type SequenceFilter = ActivityLayerFilter;
