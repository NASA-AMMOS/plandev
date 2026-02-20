import express, { Response } from "express";
import {authMiddleware, corsMiddleware, jsonErrorMiddleware} from "./middleware";
import { ActionRunner } from "./type/actionRunner";
import { extractCookies } from "./utils/auth";


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

    const cookieNames = (process.env.ACTION_COOKIE_NAMES ?? '').split(',').map(s => s.trim()).filter(Boolean);
    const forwardedCookies = extractCookies(req.headers.cookie ?? '', cookieNames);

    const fullSecrets = {
      ...secrets,
      ...forwardedCookies,
      authorization: res.locals.authorization,
      user: JSON.stringify(res.locals.user),
      userRole: res.locals.userRole
    }
    ActionRunner.addActionSecret(actionRunId, fullSecrets);

    res.status(200).send({ success: true });
  }
);

