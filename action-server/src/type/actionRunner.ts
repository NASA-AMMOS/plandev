import { runAction } from "../listeners/dbListeners";
import logger from "../utils/logger";
import type { ActionRunInsertedPayload } from "./types";

export class ActionRunner {
  // Wait up to 10 minutes for the action run associated with the secrets.
  private static WAIT_FOR_ACTION_RUN_TIMEOUT = 600000;
  // Wait up to 1 minute for the secrets associated with the action run.
  private static WAIT_FOR_SECRET_TIMEOUT = 60000;

  private static actionRuns: Record<string, ActionRunInsertedPayload> = {};
  private static actionRunQueue: Map<string, (actionRunId: string) => Promise<void>> = new Map();
  private static actionSecretsMap: Map<string, Record<string, string>> = new Map();

  static async addActionRun(actionRun: ActionRunInsertedPayload): Promise<void> {
    const actionRunId = actionRun.action_run_id;

    this.actionRuns[actionRunId] = actionRun;
    const actionRunFunc = async (runId: string) => {
      try {
        await ActionRunner.runAction(runId);
        this.deleteActionRun(runId);
      } catch (error) {
        this.deleteActionRun(runId);
      }
    };

    this.actionRunQueue.set(actionRunId, actionRunFunc);

    // If there aren't any secrets execute the action run immediately.
    if (!actionRun.has_secrets) {
      await actionRunFunc(actionRunId);
    } else {
      logger.info(`Action Run: ${actionRunId} waiting for secrets...`);
    }

    setTimeout(() => {
      if (this.actionRunQueue.get(actionRunId) !== null) {
        logger.info(`Action Run: ${actionRunId} timed out waiting for the associated action secrets.`);
        this.deleteActionRun(actionRunId);
      }
    }, this.WAIT_FOR_SECRET_TIMEOUT);
  }

  static async addActionSecret(actionRunId: string, actionSecrets: Record<string, string>): Promise<void> {
    this.actionSecretsMap.set(actionRunId, actionSecrets);

    logger.info(`Secret found for Action Run: ${actionRunId}, running action...`);

    const actionRunFunc = this.actionRunQueue.get(actionRunId);

    if (actionRunFunc) {
      setTimeout(() => {
        if (this.actionSecretsMap.get(actionRunId) !== null) {
          logger.info(`Secret for Action Run: ${actionRunId} timed out waiting for the associated action run.`);
          this.deleteActionSecret(actionRunId);
        }
      }, this.WAIT_FOR_ACTION_RUN_TIMEOUT);

      await actionRunFunc(actionRunId);
      this.deleteActionSecret(actionRunId);
    } else {
      throw new Error(`Action Run ${actionRunId} not found in queue`);
    }
  }

  static deleteActionRun(actionRunId: string): void {
    delete this.actionRuns[actionRunId];
    this.actionRunQueue.delete(actionRunId);
  }

  static deleteActionSecret(actionRunId: string): void {
    this.actionSecretsMap.delete(actionRunId);
  }

  private static async runAction(actionRunId: string): Promise<void> {
    const action = this.actionRuns[actionRunId];
    const secret = action.has_secrets ? this.actionSecretsMap.get(actionRunId) : undefined;

    this.deleteActionRun(actionRunId);
    this.deleteActionSecret(actionRunId);

    await runAction(action, secret);
  }
}
