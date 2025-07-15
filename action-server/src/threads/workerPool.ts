import { Piscina } from "piscina";
import * as path from "node:path";
import { ActionResponse, ActionTask } from "../type/types";
import { configuration } from "../config";
import logger from "../utils/logger";
import { MessageChannel, MessagePort, threadId } from "worker_threads";

export class ActionWorkerPool {
  private static piscina: Piscina<any, any>;
  public static messagePortsForActionRun: Map<string, MessagePort> = new Map();
  public static abortControllerForActionRun: Map<string, AbortController> = new Map();
  public static runningActions: Set<string> = new Set<string>();

  static setup() {
    this.piscina = new Piscina({
      filename: path.resolve(__dirname, "worker.js"),
      maxThreads: parseInt(configuration().ACTION_MAX_WORKER_NUM),
      minThreads: parseInt(configuration().ACTION_WORKER_NUM),
    });
  }

  static async submitTask(task: ActionTask): Promise<ActionResponse> {
    // todo: maintain a custom queue for enqueueing run requests *by workspace*
    if (!ActionWorkerPool.piscina) {
      throw new Error("Worker pool not initialized");
    }

    // Create a new MessageChannel for this task
    const { port1, port2 } = new MessageChannel();
    task.message_port = port2;

    this.messagePortsForActionRun.set(task.action_run_id, port1);
    const abortController = new AbortController();
    this.abortControllerForActionRun.set(task.action_run_id, abortController);

    this.setupMessagePortCallbacks(task.action_run_id);

    console.log("Submitted new task with ID " + task.action_run_id);

    try {
      // Note that transferList is for transferable types only (message ports being one)
      // and passes those objects by reference. Once passed, the source no longer owns
      // these types -- they're owned by the worker thread.
      return await ActionWorkerPool.piscina.run(task, {
        name: "runAction",
        signal: abortController.signal,
        transferList: [port2],
      });
    } catch (error) {
      logger.warn(`Action run ${task.action_run_id} did not complete:`, error);
      throw error;
    }
  }

  static setupMessagePortCallbacks(action_run_id: string) {
    // handle messages from the process, sent on the MessagePort
    const port = this.messagePortsForActionRun.get(action_run_id);
    if (port) {
      port.on("message", async (msg) => {
        if (msg.type === "cleanup_complete") {
          logger.info(`[${threadId}] Received cleanup_complete message for action_run ${action_run_id}, aborting...`);
          const abortController = this.abortControllerForActionRun.get(action_run_id);
          if (abortController == null) {
            logger.error(`No abort controller found for task ${action_run_id}. This will result in a memory leak.`);
            throw Error(`No abort controller found for task ${action_run_id}. This will result in a memory leak.`);
          } else {
            abortController.abort();
          }
          this.removeFromMaps(action_run_id);
        } else if (msg.type === "started") {
          logger.info(`[${threadId}] Worker for action_run ${action_run_id} has started...`);
          this.runningActions.add(action_run_id);
        } else if (msg.type === "finished") {
          logger.info(`[${threadId}] Worker for action_run ${action_run_id} has finished...`);
          this.removeFromMaps(action_run_id);
        }
      });
    }
  }

  static cancelTask(action_run_id: string) {
    // kill the task and delete from the abortControllers data structure
    logger.info(`Attempting to cancel action run ${action_run_id}`);

    if (!this.runningActions.has(action_run_id)) {
      // Case 1. Worker has not yet started -> use abortcontroller to remove from piscina task queue
      logger.info(`Action run ${action_run_id} has not yet started, removing it from the queue`);
      const abortController = this.abortControllerForActionRun.get(action_run_id);
      if(abortController) {
        abortController.abort();
      } else {
        logger.warn(`No abort controller found for task ${action_run_id}`);
      }
      this.removeFromMaps(action_run_id);
      return;

    } else {
      // Case 2. Worker has started, and is not completed -> ask it to close its database connection
      const port = this.messagePortsForActionRun.get(action_run_id);
      if (port) {
        logger.info(`Posting abort message for task ${action_run_id}`);
        port.postMessage({ type: "abort" });
      } else {
        logger.error(`No message port found for task ${action_run_id}, this will result in a memory leak`);
      }
    }

    // Case 3. Worker has completed, this should only happen if the user presses the button prior to the UI updating
    // with the task being completed. This case should be handled via setupMessagePortCallbacks.
  }

  static removeFromMaps(action_run_id: string) {
    logger.info(`Removing action run ${action_run_id} from maps`);
    if (this.abortControllerForActionRun.has(action_run_id)) {
      this.abortControllerForActionRun.delete(action_run_id);
    }
    if (this.messagePortsForActionRun.has(action_run_id)) {
      // Close this port, the matching port in the worker is closed in worker.ts
      this.messagePortsForActionRun.get(action_run_id)?.close();
      this.messagePortsForActionRun.delete(action_run_id);
    }
    if (this.runningActions.has(action_run_id)) {
      this.runningActions.delete(action_run_id);
    }
  }
}
