import express from "express";
import {configuration} from "./config";
import {jsExecute} from "./utils/codeRunner";
import {isActionRunRequest, validateActionRunRequest} from "./utils/validators";
import {ActionResponse} from "./type/types";
import {ActionsDbManager} from "./db";
import {Pool, PoolClient} from "pg";

import {readFile} from "fs/promises";
import {corsMiddleware, jsonErrorMiddleware} from "./middleware";

const app = express();

// Middleware for parsing JSON bodies
app.use(express.json());

// temporary CORS middleware to allow access from all origins
// TODO: set more strict CORS rules
app.use(corsMiddleware);

// Route for running a JS action
app.post("/run-action", async (req, res) => {
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


let pool: Pool | undefined;
let listenClient: PoolClient | undefined;
async function initDb() {
  // initialize a database connection pool
  ActionsDbManager.init();
  pool = ActionsDbManager.getDb();

  // todo:

  // listen for `action_run_inserted` events from postgres
  // which occur when a user inserts a row in the `action_run` table, signifying a run request
  listenClient = await pool.connect();
  listenClient.query('LISTEN action_run_inserted');

  listenClient.on('notification', async (msg) => {
    console.log("action_run_inserted");
    console.log(JSON.stringify(msg));

    // event payload contains a file path for the action file which should be run
    if(!msg || !msg.payload) return;
    const payload = JSON.parse(msg.payload);
    const filePath = payload.action_file_path;

    console.log(`path is ${filePath}`);
    const actionFile = await readFile(filePath, 'utf-8');
    console.log(actionFile);
    // todo: maintain a queue and enqueue run requests
    // todo: run the action file, put results in the same DB row and mark status as successful
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


