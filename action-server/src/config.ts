export interface Config {
  AERIE_DB: string;
  AERIE_DB_HOST: string;
  AERIE_DB_PORT: string;
  ACTION_DB_USER: string;
  ACTION_DB_PASSWORD: string;
  ACTION_LOCAL_STORE: string;
  ACTION_WORKER_NUM: string;
  ACTION_MAX_WORKER_NUM: string;
  HASURA_GRAPHQL_ADMIN_SECRET: string;
  LOG_FILE: string;
  LOG_LEVEL: string;
  MERLIN_GRAPHQL_URL: string;
  PORT: string;
  STORAGE: string;
  SEQUENCING_LOCAL_STORE: string;
  WORKSPACE_BASE_URL: string;
}

export const configuration = (): Config => {
  const { env } = process;

  return {
    AERIE_DB: env.AERIE_DB ?? "aerie",
    AERIE_DB_HOST: env.AERIE_DB_HOST ?? "postgres",
    AERIE_DB_PORT: env.AERIE_DB_PORT ?? "5432",
    ACTION_DB_USER: env.ACTION_DB_USER ?? "",
    ACTION_DB_PASSWORD: env.ACTION_DB_PASSWORD ?? "",
    ACTION_LOCAL_STORE: env.ACTION_LOCAL_STORE ?? "/usr/src/app/action_file_store",
    ACTION_WORKER_NUM: env.ACTION_WORKER_NUM ?? "1",
    ACTION_MAX_WORKER_NUM: env.ACTION_MAX_WORKER_NUM ?? "1",
    HASURA_GRAPHQL_ADMIN_SECRET: env.HASURA_GRAPHQL_ADMIN_SECRET ?? "",
    LOG_FILE: env.LOG_FILE ?? "console",
    LOG_LEVEL: env.LOG_LEVEL ?? "debug",
    MERLIN_GRAPHQL_URL: env.MERLIN_GRAPHQL_URL ?? "http://localhost:8080/graphql",
    PORT: "27186",
    STORAGE: env.STORAGE ?? "local",
    SEQUENCING_LOCAL_STORE: env.SEQUENCING_LOCAL_STORE ?? "/usr/src/app/sequencing_file_store",
    WORKSPACE_BASE_URL: env.WORKSPACE_BASE_URL ?? "http://aerie_workspace:28000",
  };
};
