import { MessagePort } from "worker_threads";
import { ActionMain, ActionParameterDefinitions, ActionSettingDefinitions } from "@nasa-jpl/plandev-actions";

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
  ACTION_RUN_ID: string;
  SEQUENCING_FILE_STORE: string;
  SECRETS?: Record<string, string> | undefined;
  WORKSPACE_BASE_URL: string;
  USERNAME?: string;
  USER_ROLE?: string;
};

export type ActionTask = {
  actionJS: string;
  action_run_id: string;
  parameters: Record<string, any>;
  settings: Record<string, any>;
  secrets?: Record<string, string>;
  auth?: string;
  workspaceId: number;
  message_port: MessagePort | null;
};

export type ActionDefinitionInsertedPayload = {
  action_definition_id: number;
  action_file_path: string;
  revision: number;
};

export type ActionRunInsertedPayload = {
  action_run_id: string;
  settings: Record<string, any>;
  parameters: Record<string, any>;
  action_definition_id: number;
  action_definition_revision: number;
  workspace_id: number;
  action_file_path: string;
  has_secrets: boolean;
};

export type ActionRunCancellationRequestPayload = {
  action_run_id: string;
  canceled: boolean;
};

export interface ActionExports {
  main: ActionMain;
  parameterDefinitions: ActionParameterDefinitions;
  settingDefinitions: ActionSettingDefinitions;
}

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
