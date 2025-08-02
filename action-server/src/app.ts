import express, { Response } from "express";
import { configuration } from "./config";
import { corsMiddleware, jsonErrorMiddleware } from "./middleware";
import { ActionWorkerPool } from "./threads/workerPool";
import { cleanup, setupListeners } from "./listeners/dbListeners";

const port = configuration().PORT;

// init express app and middleware
const app = express();
app.use(express.json()); // Middleware for parsing JSON bodies
app.use(corsMiddleware); // TODO: set more strict CORS rules
app.use(jsonErrorMiddleware);

const server = app.listen(port, async () => {
  console.debug(`Server running on port ${port}`);

  try {
    // init the pool of workers that will execute actions
    ActionWorkerPool.setup();
    // init the pg database listeners
    await setupListeners();
  } catch (error) {
    console.error("Failed to initialize application:", error);
    process.exit(1);
  }
});

app.get("/", async (req, res, next) => {
  res.send("Aerie Action Service");
});

app.get("/health", async (req, res, next) => {
  res.status(200).send();
});

// handle termination signals
process.on("SIGINT", () => cleanup(server));
process.on("SIGTERM", () => cleanup(server));
