import * as vm from "node:vm";
import { ActionResponse, ActionResults, ConsoleOutput } from "../type/types";

// function getConsoleHandlers(oldConsole: any) {
//   return {
//     ...oldConsole,
//     log: (...args: any[]) => {
//       consoleOutput.log.push(args.join(" "));
//     },
//     debug: (...args: any[]) => {
//       consoleOutput.debug.push(args.join(" "));
//     },
//     info: (...args: any[]) => {
//       consoleOutput.info.push(args.join(" "));
//     },
//     warn: (...args: any[]) => {
//       consoleOutput.warn.push(args.join(" "));
//     },
//     error: (...args: any[]) => {
//       consoleOutput.error.push(args.join(" "));
//     },
//   }
// }

export const jsExecute = async (
  code: string,
  parameters: Record<string, any>,
  settings: Record<string, any>,
  authToken: string | undefined,
): Promise<ActionResponse> => {
  /** Array to store console output. */
  const consoleOutput: ConsoleOutput = { log: [], debug: [], info: [], error: [], warn: [] };

  // create a clone of the global object (including getters/setters/non-enumerable properties)
  // to be passed to the context so it has access to eg. node built-ins
  let aerieGlobal = Object.defineProperties({ ...global }, Object.getOwnPropertyDescriptors(global));

  aerieGlobal.console = {
    ...aerieGlobal.console,
    log: (...args: any[]) => {
      consoleOutput.log.push(args.join(" "));
    },
    debug: (...args: any[]) => {
      consoleOutput.debug.push(args.join(" "));
    },
    info: (...args: any[]) => {
      consoleOutput.info.push(args.join(" "));
    },
    warn: (...args: any[]) => {
      consoleOutput.warn.push(args.join(" "));
    },
    error: (...args: any[]) => {
      consoleOutput.error.push(args.join(" "));
    },
  };

  // need to initialize exports for the module to work correctly
  aerieGlobal.exports = {};

  const context = vm.createContext(aerieGlobal);

  try {
    vm.runInContext(code, context);
    const results = await context.main(parameters, settings, authToken);
    return { results, console: consoleOutput, errors: null };
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
      console: consoleOutput,
      errors: {
        stack: errorResponse.stack,
        message: errorResponse.message,
        cause: errorResponse.cause,
      },
    });
  }
};

// todo correct return type for schemas?
export const extractSchemas = async (code: string): Promise<any> => {
  // todo: do we need to pass globals/console for this part?

  // need to initialize exports for the cjs module to work correctly
  const context = vm.createContext({exports: {}});

  try {
    vm.runInContext(code, context);
    const {paramDefs, settingDefs} = context.exports;
    return {paramDefs, settingDefs};
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

