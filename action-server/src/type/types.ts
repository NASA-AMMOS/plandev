import { Pool } from "pg";
import { MessagePort } from "worker_threads";

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

export type ActionConfig = {
  ACTION_FILE_STORE: string;
  SEQUENCING_FILE_STORE: string;
  WORKSPACE_BASE_URL: string;
};

export type ActionTask = {
  actionJS: string;
  action_run_id: string;
  parameters: Record<string, any>;
  settings: Record<string, any>;
  auth?: string;
  workspaceId: number;
  message_port: MessagePort | null;
};

export type ActionDefinitionInsertedPayload = {
  action_definition_id: number;
  action_file_path: string;
};

export type ActionRunInsertedPayload = {
  action_run_id: string;
  settings: Record<string, any>;
  parameters: Record<string, any>;
  action_definition_id: number;
  workspace_id: number;
  action_file_path: string;
};

export type ActionRunCancellationRequestPayload = {
  action_run_id: string;
  canceled: boolean;
};

export type ActionResponse =
  | {
      results: ActionResults;
      console: string[];
      errors: null;
    }
  | {
      results: null;
      console: string[];
      errors: {
        stack: string | undefined;
        message: string;
        cause: unknown;
      };
    };
