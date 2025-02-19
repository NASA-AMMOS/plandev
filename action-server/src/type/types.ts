export type ActionRunRequest = {
  actionJS: string;
  parameters: Record<string, any>;
  settings: Record<string, any>;
};

/* TODO: ActionResults should be defined by the actions API and imported */
export type ActionResults = {
  status: "FAILED" | "SUCCESS";
  data: any;
};

export type ConsoleOutput = {
  log: string[];
  debug: string[];
  info: string[];
  error: string[];
  warn: string[];
};

export type ActionResponse =
  | {
      results: ActionResults;
      console: ConsoleOutput;
      errors: null;
    }
  | {
      results: null;
      console: ConsoleOutput;
      errors: {
        stack: string | undefined;
        message: string;
        cause: unknown;
      }; // TODO: should this be an error array
    };
