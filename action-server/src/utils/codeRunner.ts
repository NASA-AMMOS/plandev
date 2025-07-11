/* eslint-disable func-style */
import * as vm from "node:vm";
import type { PoolClient } from "pg";
import { createLogger, format, transports } from "winston";
import { ActionsAPI, User } from "@nasa-jpl/aerie-actions";
import { configuration } from "../config";
import type {ActionConfig, ActionResponse } from "../type/types";

// todo put this inside a more limited closure scope or it will get reused...
// const logBuffer: string[] = [];

function injectLogger(oldConsole: any, logBuffer: string[]) {
  // inject a winston logger to be passed to the action VM, replacing its normal `console`,
  // so we can capture the console outputs and return them with the action results
  const logger = createLogger({
    level: "debug", // todo allow user to set log level
    format: format.combine(
      format.timestamp(),
      format.printf(({ level, message, timestamp }) => {
        const logLine = `${timestamp} [${level.toUpperCase()}] ${message}`;

        logBuffer.push(logLine);

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

function getGlobals() {
  const aerieGlobal = Object.defineProperties({ ...global }, Object.getOwnPropertyDescriptors(global));
  const permittedEnvironmentVariables: Record<string, string> = {};

  // Look at the global environment variables and only pass the ones with our permitted prefix to the action.
  Object.keys(global.process.env).forEach((env) => {
    if (env.startsWith(ActionsAPI.ENVIRONMENT_VARIABLE_PREFIX) && global.process.env[env]) {
      permittedEnvironmentVariables[env] = global.process.env[env];
    }
  });

  aerieGlobal.exports = {};
  aerieGlobal.require = require;
  aerieGlobal.__dirname = __dirname;
  aerieGlobal.process.env = permittedEnvironmentVariables;

  // todo: pass env variables from the parent process?
  return aerieGlobal;
}

const { ACTION_LOCAL_STORE, SEQUENCING_LOCAL_STORE , WORKSPACE_BASE_URL} = configuration();

export const jsExecute = async (
  code: string,
  parameters: Record<string, any>,
  settings: Record<string, any>,
  authToken: string | undefined,
  client: PoolClient,
  workspaceId: number,
): Promise<ActionResponse> => {
  // create a clone of the global object (including getters/setters/non-enumerable properties)
  // to be passed to the context so it has access to eg. node built-ins
  const aerieGlobal = getGlobals();
  // inject custom logger to capture logs from action run
  const logBuffer: string[] = [];

  aerieGlobal.console = injectLogger(aerieGlobal.console, logBuffer);

  const context = vm.createContext(aerieGlobal);

  try {
    vm.runInContext(code, context);
    // todo: main runs outside of VM - is that OK?
    const actionConfig: ActionConfig = { ACTION_FILE_STORE: ACTION_LOCAL_STORE, SEQUENCING_FILE_STORE: SEQUENCING_LOCAL_STORE, WORKSPACE_BASE_URL: WORKSPACE_BASE_URL };
    const user: User = {
      id: "x",
      token: authToken ?? "x",
      activeRole: "aerie-admin",
      allowedRoles: ["aerie-admin"],
      defaultRole: "aerie-admin"
    };

    console.log("Using user: ", user);
    console.log("Node version:", process.version);
    console.log("typeof fetch:", typeof fetch);
    const actionsAPI = new ActionsAPI(client, workspaceId, actionConfig, user);
    const results = await context.main(parameters, settings, actionsAPI);

    return { results, console: logBuffer, errors: null };
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

/**
 * Todo correct return type for schemas?
 */
export const extractSchemas = async (code: string): Promise<any> => {
  // todo: do we need to pass globals/console for this part?

  // need to initialize exports for the cjs module to work correctly
  const aerieGlobal = getGlobals();
  const context = vm.createContext(aerieGlobal);

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

    return Promise.resolve({
      results: null,
      errors: {
        stack: errorResponse.stack,
        message: errorResponse.message,
        cause: errorResponse.cause,
      },
    });
  }
};
