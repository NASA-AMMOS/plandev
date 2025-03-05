import express from "express";
import {configuration} from "./config";
import {extractSchemas, jsExecute} from "./utils/codeRunner";
import {isActionRunRequest, validateActionRunRequest} from "./utils/validators";
import {ActionResponse} from "./type/types";
import {ActionsDbManager} from "./db";
import {Pool, PoolClient, Notification} from "pg";

import {readFile} from "fs/promises";
import {corsMiddleware, jsonErrorMiddleware} from "./middleware";
import * as path from "node:path";

const app = express();

// Middleware for parsing JSON bodies
app.use(express.json());

// temporary CORS middleware to allow access from all origins
// TODO: set more strict CORS rules
app.use(corsMiddleware);

// Route for running a JS action
app.post("/run-action", async (req, res) => {
  // TODO: old - deprecate?
  if (!isActionRunRequest(req.body)) {
    const msg = validateActionRunRequest(req.body);
    throw new Error(msg || "Unknown");
  }
  // req.body is a valid ActionRunRequest
  const actionJS = req.body.actionJS;
  const parameters = req.body.parameters;
  const settings = req.body.settings;
  const authToken = req.header("authorization");
  if (!authToken) console.warn("No valid `authorization` header in action-run request");

  const jsRun = await jsExecute(actionJS, parameters, settings, authToken);

  res.send({
    results: jsRun.results,
    console: jsRun.console,
    errors: jsRun.errors,
  } as ActionResponse);
});

const port = configuration().PORT;

const server = app.listen(port, () => {
  console.debug(`Server running on port ${port}`);
});

app.use(jsonErrorMiddleware);

// -- begin PG event handling

async function readFileFromStore(fileName: string): Promise<string> {
  // read file from aerie file store and return [resolve] it as a string
  const fileStoreBasePath = `/usr/src/app/action_file_store`; // todo get from env
  const filePath = path.join(fileStoreBasePath, fileName);
  console.log(`path is ${filePath}`);
  return await readFile(filePath, 'utf-8');
}

type ActionDefinitionInsertedPayload = {
  action_definition_id: number,
  action_file_path: string
}
async function handleActionDefinition(payload: ActionDefinitionInsertedPayload) {
  console.log("action definition inserted");
  // pre-process and extract schemas
  const actionJS = await readFileFromStore(payload.action_file_path);
  console.log(actionJS);

  const schemas = await extractSchemas(actionJS);

  console.log(`schemas ${JSON.stringify(schemas, null, 2)}`);

  // todo: set schemas on the DB row?
  const pool = ActionsDbManager.getDb();
  const query = `
    UPDATE actions.action_definition
    SET
      parameter_schema = parameter_schema || $1::jsonb,
      settings_schema = settings_schema || $2::jsonb
    WHERE id = $3
    RETURNING *;
  `;

  try {
    const res = await pool.query(query, [
      JSON.stringify(schemas.paramDefs),
      JSON.stringify(schemas.settingDefs),
      payload.action_definition_id,
    ]);
    console.log("Updated action_definition:", res.rows[0]);
  } catch (err) {
    console.error("Error updating row:", err);
  }
}

type ActionRunInsertedPayload = {
  settings: Record<string, any>,
  parameters: Record<string, any>,
  action_definition_id: number,
  workspace_id: number,
  action_file_path: string
}

async function handleActionRun(payload: ActionRunInsertedPayload) {
  console.log("action run inserted");
  // event payload contains a file path for the action file which should be run
  const actionJS = await readFileFromStore(payload.action_file_path);
  console.log(actionJS);

  const parameters = payload.parameters;
  const settings = payload.settings;

  // TODO: how to handle auth tokens??
  // const authToken = req.header("authorization");
  // if (!authToken) console.warn("No valid `authorization` header in action-run request");

  // todo: maintain a queue and enqueue run requests
  // todo: use piscina worker pool to run in separate thread
  // todo: run the action file, put results in the same DB row and mark status as successful
  // todo: try/catch - need to handle errors manually since not in express handler?
  const jsRun = await jsExecute(actionJS, parameters, settings, "");

  const response = {
    results: jsRun.results,
    console: jsRun.console,
    errors: jsRun.errors,
  } as ActionResponse;
  console.log('finished run');
  console.log(response);
}

let pool: Pool | undefined;
let listenClient: PoolClient | undefined;
async function initDb() {
  // initialize a database connection pool
  ActionsDbManager.init();
  pool = ActionsDbManager.getDb();

  // todo: check for definitions/runs that may have been inserted while action-server was down (ie. missed notifs) & process them?

  listenClient = await pool.connect();
  // these occur when user inserts row in `action_definition`, need to pre-process to extract the schemas
  listenClient.query('LISTEN action_definition_inserted');
  // these occur when a user inserts a row in the `action_run` table, signifying a run request
  listenClient.query('LISTEN action_run_inserted');

  listenClient.on('notification', async (msg) => {
    console.info(`PG notify event: ${JSON.stringify(msg, null, 2)}`);
    if(!msg || !msg.payload) {
      console.warn(`warning: PG event with no message or payload: ${JSON.stringify(msg, null, 2)}`);
      return;
    }
    const payload = JSON.parse(msg.payload);

    if(msg.channel === "action_definition_inserted") {
      // todo should these be awaited?
      await handleActionDefinition(payload);
    } else if(msg.channel === "action_run_inserted") {
      await handleActionRun(payload);
    }
  });
}
initDb();

function cleanup() {
  console.log("shutting down...");
  if(listenClient) listenClient.release();
  server.close(() => {
    console.log("server closed.");
    process.exit(0);
  });
}
// handle termination signals
process.on("SIGINT", cleanup);
process.on("SIGTERM", cleanup);


