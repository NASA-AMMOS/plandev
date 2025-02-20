import express, { ErrorRequestHandler } from "express";
import { configuration } from "./config";
import { jsExecute } from "./utils/codeRunner";
import { isActionRunRequest, validateActionRunRequest } from "./utils/validators";
import { ActionResponse } from "./type/types";

const app = express();

// Middleware for parsing JSON bodies
app.use(express.json());

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

app.listen(port, () => {
  console.debug(`Server running on port ${port}`);
});

// custom error handling middleware so we always return a JSON error
const errorHandler: ErrorRequestHandler = (err, req, res, next) => {
  res.status(err.status || 500).json({
    error: {
      message: err.message,
      stack: err.stack,
      cause: err.cause,
    },
  });
};
app.use(errorHandler);

// temporary CORS middleware to allow access from all origins
// TODO: set more strict CORS rules
app.use((req, res, next) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  next();
});
