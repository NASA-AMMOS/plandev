import express from 'express';
import { configuration } from './config'
import { jsExecute } from "./utils/codeRunner";
import { ActionResponse, ActionResults } from "./type/types";

const app = express();

// Middleware for parsing JSON bodies
app.use(express.json());

// Simple route to test the server
app.post('/run-action', async (req, res) => {
  const actionJS = req.body.actionJS as string;
  const parameters = req.body.parameters as object
  const jsRun = await jsExecute(actionJS);
  // console.log(jsRun.console);
  // console.log(jsRun.results);
  res.send({
    results: jsRun.results,
    console : jsRun.console,
    errors : jsRun.errors
  } as ActionResponse);
});


const port = configuration().PORT;

app.listen(port, () => {
  console.debug(`Server running on port ${port}`);
});
