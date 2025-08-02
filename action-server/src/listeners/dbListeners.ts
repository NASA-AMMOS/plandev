import { readFile } from "node:fs/promises";
import type http from "node:http";
import * as path from "node:path";
import type { Pool, PoolClient } from "pg";
import { configuration } from "../config";
import { ActionsDbManager } from "../db";
import { ActionWorkerPool } from "../threads/workerPool";
import type { ActionDefinitionInsertedPayload, ActionResponse, ActionRunInsertedPayload } from "../type/types";
import { extractSchemas } from "../utils/codeRunner";
import { createLogger, format, transports } from "winston";
import logger from "../utils/logger";
import { ActionRunCancellationRequestPayload } from "../type/types";

let listenClient: PoolClient | undefined;

async function readFileFromStore(fileName: string): Promise<string> {
  // read file from aerie file store and return [resolve] it as a string
  const fileStoreBasePath = configuration().ACTION_LOCAL_STORE;
  const filePath = path.join(fileStoreBasePath, fileName);
  logger.info(`path is ${filePath}`);
  return await readFile(filePath, "utf-8");
}

async function refreshActionDefinitionSchema(payload: ActionDefinitionInsertedPayload) {
  // read the action file and extract parameter/setting schemas
  const actionJS = await readFileFromStore(payload.action_file_path);
  const schemas = await extractSchemas(actionJS);

  const pool = ActionsDbManager.getDb();
  const query = `
    UPDATE actions.action_definition
    SET
      parameter_schema = $1::jsonb,
      settings_schema = $2::jsonb
    WHERE id = $3
      RETURNING *;
  `;

  try {
    const res = await pool.query(query, [
      JSON.stringify(schemas.parameterDefinitions),
      JSON.stringify(schemas.settingDefinitions),
      payload.action_definition_id,
    ]);
    logger.info("Updated action_definition:", res.rows[0]);
  } catch (error) {
    logger.error("Error updating row:", error);
  }
}

async function cancelAction(payload: ActionRunCancellationRequestPayload) {
  ActionWorkerPool.cancelTask(payload.action_run_id);
}

async function runAction(payload: ActionRunInsertedPayload) {
  const actionRunId = payload.action_run_id;
  const actionFilePath = payload.action_file_path;
  logger.info(`action run ${actionRunId} inserted (${actionFilePath})`);
  // event payload contains a file path for the action file which should be run
  const actionJS = await readFileFromStore(actionFilePath);

  // NOTE: Authentication tokens are unavailable in PostgreSQL Listen/Notify
  // const authToken = req.header("authorization");
  // if (!authToken) console.warn("No valid `authorization` header in action-run request");

  const { parameters, settings } = payload;
  const workspaceId = payload.workspace_id;
  const pool = ActionsDbManager.getDb();
  logger.info(`Submitting task to worker pool for action run ${actionRunId}`);
  const start = performance.now();
  let run, taskError;
  try {
    run = (await ActionWorkerPool.submitTask({
      actionJS: actionJS,
      action_run_id: actionRunId,
      message_port: null,
      parameters: parameters,
      settings: settings,
      workspaceId: workspaceId,
    })) satisfies ActionResponse;
  } catch (error: any) {
    if (error?.name === "AbortError") {
      logger.info(`Action run ${actionRunId} has been canceled`);
    } else {
      logger.error("Error running task:", error);
    }
    taskError = { message: error.message, stack: error.stack };
    logger.error(JSON.stringify(taskError));
  }

  const duration = Math.round(performance.now() - start);
  const status = taskError || run?.errors ? "failed" : "success";
  logger.info(`Finished run ${actionRunId} in ${duration / 1000}s - ${status}`);
  const errorValue = JSON.stringify(taskError || run?.errors || {});

  const logStr = run ? run.console.join("\n") : "";

  // update action_run row in DB with status/results/errors/logs
  try {
    const res = await pool.query(
      `
      UPDATE actions.action_run
      SET
        status = $1,
        error = $2::jsonb,
        results = $3::jsonb,
        logs = $4,
        duration = $5
      WHERE id = $6
        RETURNING *;
    `,
      [
        status,
        errorValue,
        run && run.results ? JSON.stringify(run.results) : JSON.stringify("{}"),
        logStr,
        duration,
        payload.action_run_id,
      ],
    );
    logger.info("Updated action_run:", res.rows[0]);
  } catch (error) {
    logger.error("Error updating row:", error);
  }
}

export async function setupListeners() {
  // initialize a database connection pool
  const pool = ActionsDbManager.getDb();

  // todo: check for definitions/runs that may have been inserted while action-server was down (ie. missed notifs) & process them?

  // save listenClient as a global so we can close it on cleanup if necessary
  listenClient = await pool.connect();
  // these occur when user inserts row in `action_definition`, need to pre-process to extract the schemas
  listenClient.query("LISTEN action_definition_inserted");
  // these occur when a user inserts a row in the `action_run` table, signifying a run request
  listenClient.query("LISTEN action_run_inserted");
  // these occur when a user sets the `canceled` of an `action_run` to true, signifying a cancellation request
  listenClient.query("LISTEN action_run_cancel_requested");

  listenClient.on("notification", async (msg) => {
    console.info(`PG notify event: ${JSON.stringify(msg, null, 2)}`);
    if (!msg.payload) {
      console.warn(`warning: PG event with no message or payload: ${JSON.stringify(msg, null, 2)}`);
      return;
    }
    const payload = JSON.parse(msg.payload);

    if (msg.channel === "action_definition_inserted") {
      await refreshActionDefinitionSchema(payload);
    } else if (msg.channel === "action_run_inserted") {
      await runAction(payload);
    } else if (msg.channel === "action_run_cancel_requested") {
      await cancelAction(payload);
    }
  });
  logger.info("Initialized PG event listeners");
}

export function cleanup(server: http.Server) {
  console.log("shutting down...");
  if (listenClient) {
    listenClient.release();
  }
  server.close(() => {
    logger.info("server closed.");
    process.exit(0);
  });
}
