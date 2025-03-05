import type { Pool, PoolConfig } from "pg";
import pg from "pg";
// import getLogger from './utils/logger.js';
// import { getEnv } from './env.js';

const { Pool: DbPool } = pg;

const {
  AERIE_DB_HOST: host,
  AERIE_DB_PORT: port,
  SEQUENCING_DB_USER: user,
  SEQUENCING_DB_PASSWORD: password,
  // } = getEnv();
} = {
  AERIE_DB_HOST: "postgres",
  SEQUENCING_DB_PASSWORD: "postgres",
  AERIE_DB_PORT: "5432",
  SEQUENCING_DB_USER: "postgres",
};
// const logger = getLogger('packages/db/db');
const logger = console;

export class ActionsDbManager {
  private static pool: Pool;

  static getDb(): Pool {
    return ActionsDbManager.pool;
  }

  static init() {
    try {
      const config: PoolConfig = {
        database: "aerie",
        host,
        password,
        port: parseInt(port, 10),
        user,
      };

      logger.info(`Postgres Config:`);
      logger.info(`
      {
         database: ${config.database},
         host: ${config.host},
         port: ${config.port}
      }`);

      ActionsDbManager.pool = new DbPool(config);
    } catch (error) {
      logger.error(error);
    }
  }
}
