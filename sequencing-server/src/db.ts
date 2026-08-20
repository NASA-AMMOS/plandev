import type { Pool, PoolConfig } from 'pg';
import pg from 'pg';
import getLogger from './utils/logger.js';
import { getEnv } from './env.js';

const { Pool: DbPool } = pg;

const {
  PLANDEV_DB_HOST: host,
  PLANDEV_DB_PORT: port,
  SEQUENCING_DB_USER: user,
  SEQUENCING_DB_PASSWORD: password,
} = getEnv();

const logger = getLogger('packages/db/db');

export class DbExpansion {
  private static pool: Pool;

  static getDb(): Pool {
    return DbExpansion.pool;
  }

  static init() {
    try {
      const config: PoolConfig = {
        database: 'aerie',
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

      DbExpansion.pool = new DbPool(config);
    } catch (error) {
      logger.error(error);
    }
  }
}
