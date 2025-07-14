import { runAction } from "../listeners/dbListeners";
import logger from "../utils/logger";
import type { ActionRunInsertedPayload } from "./types";

export class ActionRunner {
  private static SECRET_RUN_TIMEOUT = 60000;

  private static actionRuns: Record<string, ActionRunInsertedPayload> = {};
  private static actionRunQueue: Record<string, (actionRunId: string) => Promise<void>> = {};
  private static actionSecrets: Record<string, Record<string, string>> = {};

  static async addActionRun(actionRun: ActionRunInsertedPayload): Promise<void> {
    const actionRunId = actionRun.action_run_id;

    this.actionRuns[actionRunId] = actionRun;
    this.actionRunQueue[actionRunId] = async (runId: string) => {
      try {
        await ActionRunner.runAction(runId);
        this.deleteActionRun(runId);
      } catch (error) {
        this.deleteActionRun(runId);
      }
    };

    // If there aren't any secrets execute the action run immediately.
    if (!actionRun.has_secrets) {
      await this.actionRunQueue[actionRunId](actionRunId);
    } else {
      logger.info(`Action Run: ${actionRunId} waiting for secrets...`);
    }

    setTimeout(() => {
      logger.info(`Action Run: ${actionRunId} timed out waiting for the associated action secrets.`);
      this.deleteActionRun(actionRunId);
    }, this.SECRET_RUN_TIMEOUT);
  }

  static async addActionSecret(actionRunId: string, actionSecrets: Record<string, string>): Promise<void> {
    this.actionSecrets[actionRunId] = actionSecrets;

    logger.info(`Secret found for Action Run: ${actionRunId}, running action...`);

    await this.actionRunQueue[actionRunId](actionRunId);

    setTimeout(() => {
      logger.info(`Action Run: ${actionRunId} timed out waiting for the associated action run.`);
      this.deleteActionSecret(actionRunId);
    }, this.SECRET_RUN_TIMEOUT);
  }

  static deleteActionRun(actionRunId: string): void {
    delete this.actionRuns[actionRunId];
    delete this.actionRunQueue[actionRunId];
  }

  static deleteActionSecret(actionRunId: string): void {
    delete this.actionSecrets[actionRunId];
  }

  private static async runAction(actionRunId: string): Promise<void> {
    const action = this.actionRuns[actionRunId];

    await runAction(action, action.has_secrets ? this.actionSecrets[actionRunId] : undefined);

    this.deleteActionRun(actionRunId);
    this.deleteActionSecret(actionRunId);
  }
}
