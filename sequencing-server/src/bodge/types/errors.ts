import type { ErrorTypes } from '../utilities/errors';

export interface BaseError {
  data?: unknown;
  message: string;
  timestamp: string;
  trace?: string;
  type: ErrorTypes;
}

export interface CaughtError extends BaseError {
  type: ErrorTypes.CAUGHT_ERROR;
}

export interface AnchorValidationError extends BaseError {
  activityId: number;
  type: ErrorTypes.ANCHOR_VALIDATION_ERROR;
}

export type ActivityValidationStatus = 'complete' | 'pending';

export interface ActivityValidationErrors {
  activityId: number;
  errors: (ActivityDirectiveValidationFailures | AnchorValidationError)[];
  status: ActivityValidationStatus;
  type: string;
}

export interface SchedulingError extends BaseError {
  data: {
    errors: {
      [activityId: string]: unknown;
    };
    success: boolean;
  };
}

export interface SimulationDatasetError extends BaseError {
  data: {
    elapsedTime?: string;
    errors?: {
      [activityId: string]: unknown;
    };
    success?: boolean;
    utcTimeDoy?: string;
  };
}

export interface ActivityDirectiveInstantiationError {
  extraneousArguments: string[];
  missingArguments: string[];
  unconstructableArguments: {
    failure: string;
    name: string;
  }[];
}

export interface ActivityDirectiveUnknownTypeError {
  noSuchActivityError: {
    activity_type: string;
    message: string;
  };
}

export interface ActivityDirectiveValidationNoticesError {
  validationNotices: {
    message: string;
    subjects: string[];
  }[];
}

export interface ActivityDirectiveValidationFailureStatus {
  directive_id: number;
  plan_id: number;
  status: ActivityValidationStatus;
  validations: ActivityDirectiveValidationFailures;
}

export type ActivityDirectiveValidationFailures =
  | ActivityDirectiveInstantiationFailure
  | ActivityDirectiveUnknownTypeFailure
  | ActivityDirectiveValidationNoticesFailure;

interface BaseActivityDirectiveValidation {
  success: boolean;
}

interface ActivityDirectiveValidationFailure extends BaseActivityDirectiveValidation {
  success: false;
  type: ErrorTypes;
}

export interface ActivityDirectiveInstantiationFailure extends ActivityDirectiveValidationFailure {
  errors: ActivityDirectiveInstantiationError;
  type: ErrorTypes.INSTANTIATION_ERRORS;
}

export interface ActivityDirectiveUnknownTypeFailure extends ActivityDirectiveValidationFailure {
  errors: ActivityDirectiveUnknownTypeError;
  type: ErrorTypes.NO_SUCH_ACTIVITY_TYPE;
}

export interface ActivityDirectiveValidationNoticesFailure extends ActivityDirectiveValidationFailure {
  errors: ActivityDirectiveValidationNoticesError;
  type: ErrorTypes.VALIDATION_NOTICES;
}

export interface ActivityErrorCounts {
  all?: number;
  extra: number;
  invalidAnchor: number;
  invalidParameter: number;
  missing: number;
  outOfBounds: number;
  pending: number;
  wrongType: number;
}

export type ActivityErrorCategories = keyof ActivityErrorCounts;

export interface ActivityErrorRollup {
  errorCounts: ActivityErrorCounts;
  id: number;
  location: string[];
  type: string;
}
