import * as vm from "node:vm";
import { ActionResponse, ActionResults, ConsoleOutput } from "../type/types";

export const jsExecute = async (
  code: string,
  parameters: Record<string, any>,
  settings: Record<string, any>,
  authToken: string | undefined,
): Promise<ActionResponse> => {
  /** Array to store console output. */
  const consoleOutput: ConsoleOutput = { log: [], debug: [], info: [], error: [], warn: [] };
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
