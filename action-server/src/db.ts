import type { Pool } from "pg";
import pg from "pg";
import { configuration } from "./config";
import logger from "./utils/logger";

const { AERIE_DB, AERIE_DB_HOST, AERIE_DB_PORT, ACTION_DB_USER, ACTION_DB_PASSWORD } = configuration();

export class ActionsDbManager {
  private static pool: Pool;

  static getDb(): Pool {
    // singleton DB pool, shared by the process
    // saved as a static to prevent accidental re-initialization
    if (ActionsDbManager.pool) return ActionsDbManager.pool;

    try {
      logger.info(`Creating PG pool`);
      ActionsDbManager.pool = new pg.Pool({
        host: AERIE_DB_HOST,
        port: parseInt(AERIE_DB_PORT, 5432),
        database: AERIE_DB,
        user: ACTION_DB_USER,
        password: ACTION_DB_PASSWORD,
        max: 3,
        min: 3,
      });
      return ActionsDbManager.pool;
    } catch (error) {
      logger.error(error);
      throw error;
    }
  }
}
