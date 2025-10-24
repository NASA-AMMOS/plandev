import { ActionWorkerPool } from "./threads/workerPool";
import { cleanup, setupListeners } from "./listeners/dbListeners";
import {app}  from "./app";
import {configuration} from "./config";

const port = configuration().PORT;

async function start() {
  try {
    // init the pool of workers that will execute actions
    ActionWorkerPool.setup();
    // init the pg database listeners
    await setupListeners();
    // start the express app
    const server = app.listen(port, () => {
      console.debug(`Server running on port ${port}`);
    });
    // handle termination signals, cleanup server/listeners when app is terminated
    process.on("SIGINT", () => cleanup(server));
    process.on("SIGTERM", () => cleanup(server));
  } catch (err) {
    console.error("Failed to initialize application:", err);
    process.exit(1);
  }
}

start();
