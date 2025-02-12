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
