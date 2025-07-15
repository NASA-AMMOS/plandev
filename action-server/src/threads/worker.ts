import { threadId } from "worker_threads";
import { jsExecute } from "../utils/codeRunner";
import { ActionResponse, ActionTask } from "../type/types";
import logger from "../utils/logger";
import { configuration } from "../config";
import pg from "pg";
import { ActionWorkerPool } from "./workerPool";
import { MessageChannel, MessagePort } from "worker_threads";

const { AERIE_DB, AERIE_DB_HOST, AERIE_DB_PORT, ACTION_DB_USER, ACTION_DB_PASSWORD } = configuration();

let dbPool: pg.Pool | null = null;
let dbClient: pg.PoolClient | null = null;

function getDbPool() {
  // we currently have no way to pass DB connections from a parent-managed db pool to the worker process (preferred).
  // (Pools/clients contain data that cannot be serialized, causing a DataCloneError if passed to the worker)
  // instead, when an action is run, a worker creates a new Pool with one client, to get its own connection,
  // and closes it again when the action run is complete.

  if (dbPool) return dbPool;

  dbPool = new pg.Pool({
    host: AERIE_DB_HOST,
    port: parseInt(AERIE_DB_PORT, 5432),
    database: AERIE_DB,
    user: ACTION_DB_USER,
    password: ACTION_DB_PASSWORD,
    // should have exactly one client/connection
    min: 1,
    max: 1,
  });

  dbPool.on("error", (err) => {
    logger.error(`[${threadId}] pool error:`, err);
  });

  return dbPool;
}

async function getDbClient(): Promise<pg.PoolClient> {
  if (dbClient) return dbClient;

  const pool = getDbPool();
  dbClient = await pool.connect();
  return dbClient;
}

async function releaseDbPoolAndClient(): Promise<void> {
  if (dbClient) {
    dbClient.release();
    dbClient = null;
  }

  if (dbPool) {
    logger.info(`[${threadId}] Shutting down worker DB pool`);
    await dbPool.end();
    dbPool = null;
  }
}

export async function runAction(task: ActionTask): Promise<ActionResponse> {
  logger.info(`Worker [${threadId}] running task`);
  logger.info(`Parameters: ${JSON.stringify(task.parameters, null, 2)}`);
  logger.info(`Settings: ${JSON.stringify(task.settings, null, 2)}`);

  // Set up the message listener
  if (task.message_port) {
    task.message_port.on("message", async (msg) => {
      if (msg.type === "abort") {
        logger.info(`[Action Run ${task.action_run_id}, Thread ${threadId}] Received abort message, attempting cleanup...`);
        try {
          await releaseDbPoolAndClient();
          logger.info(`[Action Run ${task.action_run_id}, Thread ${threadId}] Async cleanup complete`);
        } catch (err) {
          logger.error(`[Action Run ${task.action_run_id}, Thread ${threadId}] Error during async cleanup`, err);
        }
        task.message_port?.postMessage({ type: "cleanup_complete" });
        task.message_port?.close();
        logger.info(`[Action Run ${task.action_run_id}, Thread ${threadId}] Message port closed. Aborting now...`);
        // This ensures node has flushed stdout buffer before continuing:
        await new Promise((r) => process.stdout.write("", r));
      }
    });

    // Send "I'm alive" message back to main thread before starting major work
    task.message_port.postMessage({ type: "started" });
  }

  const client = await getDbClient();
  logger.info(`[Action Run ${task.action_run_id}, Thread ${threadId}] Connected to DB`);

  // update this action run in the database to show "incomplete"
  try {
    logger.info(`[Action Run ${task.action_run_id}, Thread ${threadId}] Attempting to mark action run as incomplete`);
    const res = await client.query(
      `
      UPDATE actions.action_run
      SET
        status = $1
      WHERE id = $2
        RETURNING *;
    `,
      ["incomplete", task.action_run_id],
    );
    logger.info(`[Action Run ${task.action_run_id}, Thread ${threadId}] Updated action_run as incomplete`);
  } catch (error) {
    logger.error(`[Action Run ${task.action_run_id}, Thread ${threadId}] Error updating status of action_run:`, error);
  }

  let jsRun: ActionResponse;
  try {
    jsRun = await jsExecute(task.actionJS, task.parameters, task.settings, task.auth, client, task.workspaceId);
    logger.info(`[Action Run ${task.action_run_id}, Thread ${threadId}] done executing`);
    await releaseDbPoolAndClient();
    logger.info(`[Action Run ${task.action_run_id}, Thread ${threadId}] released DB connection`);
    // Send "I'm finished" back to main thread:
    task.message_port?.postMessage({ type: "finished" });
    task.message_port?.close();
    return jsRun;
  } catch (e) {
    logger.info(`[Action Run ${task.action_run_id}, Thread ${threadId}] Error while executing`);
    await releaseDbPoolAndClient();
    logger.info(`[Action Run ${task.action_run_id}, Thread ${threadId}] Released DB connection`);
    // Send "I'm finished" back to main thread:
    task.message_port?.postMessage({ type: "finished" });
    task.message_port?.close();
    throw e;
  }
}
