import express from "express";
import { configuration } from "./config";
import {authMiddleware, corsMiddleware, jsonErrorMiddleware} from "./middleware";
import { ActionRunner } from "./type/actionRunner";
import { extractCookies } from "./utils/auth";
import logger from "./utils/logger";


// init express app and middleware
export const app = express();
app.use(express.json()); // Middleware for parsing JSON bodies
app.use(corsMiddleware); // TODO: set more strict CORS rules

app.get("/", async (req, res, next) => {
  res.send("PlanDev Action Service");
});

app.get("/health", async (req, res, next) => {
  res.status(200).send();
});

app.post(
  "/secrets",
  authMiddleware,
  (req, res, next) => {
    const { action_run_id, secrets } = req.body;
    const actionRunId = action_run_id as string;

    const { ACTION_COOKIE_NAMES } = configuration();
    const forwardedCookies = extractCookies(req.headers.cookie ?? '', ACTION_COOKIE_NAMES);

    const fullSecrets = {
      ...secrets,
      authorization: res.locals.authorization,
      cookies: forwardedCookies,
      user: JSON.stringify(res.locals.user),
      userRole: res.locals.userRole
    }
    const actionRunFunc = ActionRunner.addActionSecret(actionRunId, fullSecrets);

    actionRunFunc(actionRunId).finally(() => {
      ActionRunner.deleteActionSecret(actionRunId);
    });

    res.status(200).send({ success: true });
  }
);

// attach error-handling middleware AFTER routes
app.use(jsonErrorMiddleware);
