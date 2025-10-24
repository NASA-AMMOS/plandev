import express, { Response } from "express";
import {authMiddleware, corsMiddleware, jsonErrorMiddleware} from "./middleware";
import { ActionRunner } from "./type/actionRunner";


// init express app and middleware
export const app = express();
app.use(express.json()); // Middleware for parsing JSON bodies
app.use(corsMiddleware); // TODO: set more strict CORS rules
app.use(jsonErrorMiddleware);

app.get("/", async (req, res, next) => {
  res.send("Aerie Action Service");
});

app.get("/health", async (req, res, next) => {
  res.status(200).send();
});

app.post(
  "/secrets",
  authMiddleware,
  async (req, res, next) => {
    const { action_run_id, secrets } = req.body;
    const actionRunId = action_run_id as string;

    ActionRunner.addActionSecret(actionRunId, secrets as Record<string, string>);

    res.status(200).send({ success: true });
  }
);

// export default app;
