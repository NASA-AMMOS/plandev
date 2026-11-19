export enum ErrorType {
  INTERNAL = 'INTERNAL_ERROR',
  VALIDATION = 'ENDPOINT_VALIDATION_EXCEPTION',
  DATABASE = 'DATABASE_EXCEPTION',
  NO_SUCH_SIM_DATASET = 'NO_SUCH_SIMULATION_DATASET',
  INVALID_SIM = 'INVALID_SIMULATION_DATASET',
  INVALID_CONSTRAINTS = 'INVALID_CONSTRAINTS_RUN',
  EXPANSION_RUN_FAILURE = 'EXPANSION_RUN_FAILURE',
  UNSUPPORTED_LANGUAGE = 'UNSUPPORTED_SEQUENCING_LANGUAGE'
}

export type FormattedError = {
  message: string,
  extensions: {
    type: ErrorType,
    message: string,
    timestamp: Date,
    service: "sequencing_server",
    cause?: string,
    trace?: string,
    data?: any
  }
}

export class ErrorWithStatusCode extends Error {
  public readonly statusCode: number;
  public readonly type: ErrorType
  public readonly date: Date;
  public override readonly cause: Error | undefined;
  public readonly data?: any;

  constructor(message: string, statusCode: number, type: ErrorType = ErrorType.INTERNAL, data?: any, cause?: Error) {
    super(message);
    this.statusCode = statusCode;
    this.type = type;
    this.cause = cause ?? undefined;
    this.data = data;
    this.date = new Date();
  }

  /**
   * Format this object into the Hasura-styled FormattedError.
   * Relevant ticket going over said format: https://github.com/NASA-AMMOS/plandev/issues/1732
   */
  format(): FormattedError {
    const formattedError : FormattedError = {
      message: this.message,
      extensions: {
        type: this.type,
        message: this.message,
        timestamp: this.date,
        service: "sequencing_server",
      }
    }

    if(this.stack !== undefined) {
      formattedError.extensions.trace = this.stack;
    } else if(this.cause?.stack !== undefined) {
      formattedError.extensions.trace = this.cause.stack;
    }
    if(this.data !== undefined) {
      formattedError.extensions.data = this.data;
    }

    return formattedError
  }
}
