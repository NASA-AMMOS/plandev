import type { ValueSchema } from './schema';

export type EffectiveArguments = {
  arguments: ArgumentsMap;
  errors: ParametersErrorMap;
  success: boolean;
};

export type FormParameter<T = ValueSchema> = {
  errors: string[] | null;
  file?: File;
  index?: number;
  key?: string;
  name: string;
  order: number;
  required?: boolean;
  schema: T;
  value: Argument;
  valueSource: ValueSource;
};

export type Argument = any;

export type ArgumentsMap = Record<ParameterName, Argument>;

export type Parameter = { order: number; schema: ValueSchema; unit?: string };
export type ComputedParameter = { order: number; schema: ValueSchema; units?: Record<ParameterName, string> };

export type ParameterError = { message: string; schema: ValueSchema };

export type ParametersErrorMap = Record<ParameterName, ParameterError>;

export type ParameterName = string;

export type RequiredParametersList = ParameterName[];

export type ParametersMap = Record<ParameterName, Parameter>;
export type ComputedParametersMap = Record<ParameterName, ComputedParameter>;

export type ParameterValidationError = {
  message: string;
  subjects: string[];
};

export type ParameterValidationResponse = {
  errors?: ParameterValidationError[];
  success: boolean;
};

export type ParameterType = 'activity' | 'simulation';
export type ValueSource = 'user on model' | 'user on preset' | 'preset' | 'mission' | 'none';
