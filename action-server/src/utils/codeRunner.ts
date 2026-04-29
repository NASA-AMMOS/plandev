/* eslint-disable func-style */
import * as vm from "node:vm";
import type { PoolClient } from "pg";
import { createLogger, format, transports } from "winston";
import { ActionsAPI, ActionParameterDefinitions, ActionSettingDefinitions } from "@nasa-jpl/aerie-actions";
import { configuration } from "../config";
import type { ActionConfig, ActionExports, ActionResponse } from "../type/types";

const { ACTION_LOCAL_STORE, SEQUENCING_LOCAL_STORE, WORKSPACE_BASE_URL } = configuration();

function injectLogger(oldConsole: any, logBuffer: string[], secrets?: Record<string, any> | undefined) {
  // secrets may be passed as last argument, to be censored in the logs
  const censoredSecrets = { ...(secrets || {}) };

  // inject a winston logger to be passed to the action VM, replacing its normal `console`,
  // so we can capture the console outputs and return them with the action results
  const logger = createLogger({
    level: "debug", // todo allow user to set log level
    format: format.combine(
      format.timestamp(),
      format.printf(({ level, message, timestamp }) => {
        const logLine = `${timestamp} [${level.toUpperCase()}] `;
        let output = message as string;

        // If the action has secrets filter them out of the log.
        if (Object.keys(censoredSecrets).length > 0) {
          const secretValues = Object.values(censoredSecrets);

          for (const secretValue of secretValues) {
            if(secretValue.length) {
              output = output.replaceAll(secretValue, "*****");
            }
          }
        }

        logBuffer.push(logLine + output);

        return logLine;
      }),
    ),
    // todo log to console if log level is debug
    transports: [new transports.Console()], // optional, for debugging
  });

  return {
    ...oldConsole,
    log: (...args: any[]) => logger.info(args.join(" ")),
    debug: (...args: any[]) => logger.debug(args.join(" ")),
    info: (...args: any[]) => logger.info(args.join(" ")),
    warn: (...args: any[]) => logger.warn(args.join(" ")),
    error: (...args: any[]) => logger.error(args.join(" ")),
  };
}

type ActionGlobalContext = Partial<typeof globalThis> & {
  console: Console;
  [key: string]: unknown;
};

function getGlobals() {
  // Look at the global environment variables and only pass the ones with our permitted prefix to the action.
  const permittedEnvironmentVariables: Record<string, string> = {};
  Object.keys(globalThis.process.env).forEach((env) => {
    if (env.startsWith(ActionsAPI.ENVIRONMENT_VARIABLE_PREFIX) && globalThis.process.env[env]) {
      permittedEnvironmentVariables[env] = globalThis.process.env[env];
    }
  });

  // create a new context (globals) object to give the action when running
  // including copies of most global context, except only the permitted subset of env vars
  const aerieGlobal: ActionGlobalContext  = {
    console,
    exports: {},
    require,
    __dirname,
    process: {
      ...process,
      env: { ...permittedEnvironmentVariables },
    },
  };
  Object.setPrototypeOf(aerieGlobal, globalThis);

  return aerieGlobal;
}

export const jsExecute = async (
  code: string,
  parameters: Record<string, any>,
  settings: Record<string, any>,
  actionRunId: string,
  client: PoolClient,
  workspaceId: number,
  secrets: Record<string, string> | undefined,
): Promise<ActionResponse> => {
  // create a clone of the global object
  // to be passed to the context so it has access to eg. node built-ins
  const aerieGlobal = getGlobals();

  // don't treat role as a secret so we don't censor it
  let userRole = "";
  if(secrets && secrets.userRole) {
    userRole = secrets.userRole;
    delete secrets.userRole;
  }

  // inject custom logger to capture logs from action run
  const logBuffer: string[] = [];
  aerieGlobal.console = injectLogger(aerieGlobal.console, logBuffer, secrets);

  const context = vm.createContext(aerieGlobal);

  let username = "";
  if(secrets && secrets.user) {
    try {
      const user = JSON.parse(secrets.user) || {};
      username = user?.username || "";
    } catch(e) {
      aerieGlobal.console.warn("Could not retrieve username from user token");
    }
  }

  try {
    vm.runInContext(code, context);
    // todo: main runs outside of VM - is that OK?
    const actionConfig: ActionConfig = {
      ACTION_FILE_STORE: ACTION_LOCAL_STORE,
      ACTION_RUN_ID: actionRunId,
      SECRETS: secrets,
      SEQUENCING_FILE_STORE: SEQUENCING_LOCAL_STORE,
      USERNAME: username,
      USER_ROLE: userRole,
      WORKSPACE_BASE_URL: WORKSPACE_BASE_URL
    };


    // todo: add some handling/validation/error checking here to make sure exports are not malformed
    const {parameterDefinitions, settingDefinitions} = context.exports as ActionExports;
    // validate param & setting values to make sure enums are valid and required params are included
    const paramValidateErrors = validateParameters(parameters, parameterDefinitions, "parameter");
    const settingValidateErrors = validateParameters(settings, settingDefinitions, "setting");
    const combinedValidationErrors = (paramValidateErrors || settingValidateErrors) ?
      [paramValidateErrors || "", settingValidateErrors || ""].filter(Boolean).join("\n") :
      null

    if(combinedValidationErrors) { throw new Error(combinedValidationErrors); }

    const actionsAPI = new ActionsAPI(client, workspaceId, actionConfig);
    const results = await context.main(parameters, settings, actionsAPI);

    // clone + serialize results returned from action
    // to sanitize unserializable things in object (todo: investigate more)
    const cleanResults = results ? JSON.parse(JSON.stringify(results)) : results;

    return { results: cleanResults, console: logBuffer, errors: null };

  } catch (error: any) {
    // wrap `throw 10` into a `new throw(10)`
    let errorResponse: Error;

    if ((error !== null && typeof error !== "object") || !("message" in error && "stack" in error)) {
      errorResponse = new Error(String(error));
    } else {
      errorResponse = error;
    }
    // also push errors into run logs - useful to have them there
    if (errorResponse.message) {
      aerieGlobal.console.error(errorResponse.message);
    }
    if (errorResponse.stack) {
      aerieGlobal.console.error(errorResponse.stack);
    }
    if (errorResponse.cause) {
      aerieGlobal.console.error(errorResponse.cause);
    }

    return Promise.resolve({
      results: null,
      console: logBuffer,
      errors: {
        stack: errorResponse.stack,
        message: errorResponse.message,
        cause: errorResponse.cause,
      },
    });
  }
};

function validateParameters(
    parameters: Record<string, any>,
    parameterDefinitions: ActionParameterDefinitions | ActionSettingDefinitions,
    typeStr: string
): null | string {
  // walk through all the parameterDefinitions & setting definitions
  // if definition is required, make sure it's in settings/params
  // if definition is enum-typed, make sure value (if provided) is in valid enum list
  let errors: string[] = [];
  if (!parameterDefinitions) {
    return `${typeStr} definitions must be exported from your action as \`${typeStr}Definitions\``;
  }

  for (const paramDefKey in parameterDefinitions) {
    const hasParamValue = paramDefKey in parameters && parameters[paramDefKey] !== undefined && parameters[paramDefKey] !== "";
    const paramDefinition = parameterDefinitions[paramDefKey];
    if (paramDefinition.required === true && !hasParamValue) {
      errors.push(`Missing value for required ${typeStr} "${paramDefKey}"`);
    }

    if (paramDefinition.type === "variant") {
      const allowedValues = paramDefinition.variants;

      if (allowedValues !== undefined && allowedValues.length > 0) {
        // `undefined` is also a valid value for non-required variants
        allowedValues.push({key: undefined, label: "undefined"});
        const paramValue = parameters[paramDefKey];

        if (!allowedValues.some(({ key }) => key === paramValue)) {
          const allowedStr = allowedValues.map(({ key }) => key).join(", ");

          errors.push(`${paramValue} is not a valid value for ${typeStr} ${paramDefKey}, must be one of: ${allowedStr}`);
        }
      } else {
        errors.push(`${typeStr} definition for ${paramDefKey} is a variant type, but has no allowed variants (values).`);
      }
    }
  }

  // future: pass context & call custom param validate funcs if they exist
  // construct strings for combined validation errors
  return errors.length ? errors.join("\n") : null;
}

/**
 * Todo correct return type for schemas?
 */
export const extractSchemas = (code: string, providedContext?: vm.Context): any => {
  // todo: do we need to pass console for this part?

  const context = providedContext || vm.createContext(getGlobals());

  try {
    vm.runInContext(code, context);
    const { parameterDefinitions, settingDefinitions } = context.exports;

    return { parameterDefinitions, settingDefinitions };
  } catch (error: any) {
    // wrap `throw 10` into a `new throw(10)`
    let errorResponse: Error;

    if ((error !== null && typeof error !== "object") || !("message" in error && "stack" in error)) {
      errorResponse = new Error(String(error));
    } else {
      errorResponse = error;
    }

    return {
      results: null,
      errors: {
        stack: errorResponse.stack,
        message: errorResponse.message,
        cause: errorResponse.cause,
      },
    };
  }
};
