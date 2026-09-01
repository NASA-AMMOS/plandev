import type { SequencingLanguage } from "./lib/mustache/enums/language";

export type Env = {
  ACTION_DB_USER: string;
  ACTION_DB_PASSWORD: string;
  DICTIONARY_PARSER_PLUGIN: string,
  HASURA_GRAPHQL_ADMIN_SECRET: string;
  LOG_FILE: string;
  LOG_LEVEL: string;
  MERLIN_GRAPHQL_URL: string;
  PORT: string;
  PLANDEV_DB: string;
  PLANDEV_DB_HOST: string;
  PLANDEV_DB_PORT: string;
  SEQUENCING_DB_USER: string;
  SEQUENCING_DB_PASSWORD: string;
  SEQUENCING_LANGUAGE: SequencingLanguage;
  STORAGE: string;
};

export const defaultEnv: Env = {
  ACTION_DB_USER: '',
  ACTION_DB_PASSWORD: '',
  DICTIONARY_PARSER_PLUGIN : '/ampcs-dictionary-parser/ampcs-parser.js',
  HASURA_GRAPHQL_ADMIN_SECRET: '',
  LOG_FILE: 'console',
  LOG_LEVEL: 'info',
  MERLIN_GRAPHQL_URL: 'http://hasura:8080/v1/graphql',
  PORT: '27184',
  PLANDEV_DB: 'plandev',
  PLANDEV_DB_HOST: 'localhost',
  PLANDEV_DB_PORT: '5432',
  SEQUENCING_DB_PASSWORD: '',
  SEQUENCING_DB_USER: '',
  SEQUENCING_LANGUAGE: "SeqN" as SequencingLanguage,
  STORAGE: 'sequencing_file_store',
};

export function getEnv(): Env {
  const { env } = process;
  const ACTION_DB_USER = env['ACTION_DB_USER'] ?? defaultEnv.ACTION_DB_USER;
  const ACTION_DB_PASSWORD = env['ACTION_DB_PASSWORD'] ?? defaultEnv.ACTION_DB_PASSWORD;
  const DICTIONARY_PARSER_PLUGIN = env['DICTIONARY_PARSER_PLUGIN'] ?? defaultEnv.DICTIONARY_PARSER_PLUGIN;
  const HASURA_GRAPHQL_ADMIN_SECRET = env['HASURA_GRAPHQL_ADMIN_SECRET'] ?? defaultEnv.HASURA_GRAPHQL_ADMIN_SECRET;
  const LOG_FILE = env['LOG_FILE'] ?? defaultEnv.LOG_FILE;
  const LOG_LEVEL = env['LOG_LEVEL'] ?? defaultEnv.LOG_LEVEL;
  const MERLIN_GRAPHQL_URL = env['MERLIN_GRAPHQL_URL'] ?? defaultEnv.MERLIN_GRAPHQL_URL;
  const PORT = env['SEQUENCING_SERVER_PORT'] ?? defaultEnv.PORT;
  const PLANDEV_DB = env['PLANDEV_DB'] ?? env['AERIE_DB'] ?? defaultEnv.PLANDEV_DB;
  const PLANDEV_DB_HOST = env['PLANDEV_DB_HOST'] ?? env['AERIE_DB_HOST'] ?? defaultEnv.PLANDEV_DB_HOST;
  const PLANDEV_DB_PORT = env['PLANDEV_DB_PORT'] ?? env['AERIE_DB_PORT'] ?? defaultEnv.PLANDEV_DB_PORT;
  const SEQUENCING_DB_USER = env['SEQUENCING_DB_USER'] ?? defaultEnv.SEQUENCING_DB_USER;
  const SEQUENCING_DB_PASSWORD = env['SEQUENCING_DB_PASSWORD'] ?? defaultEnv.SEQUENCING_DB_PASSWORD;
  const SEQUENCING_LANGUAGE = env['SEQUENCING_LANGUAGE'] as SequencingLanguage ?? defaultEnv.SEQUENCING_LANGUAGE;
  const STORAGE = env['SEQUENCING_LOCAL_STORE'] ?? defaultEnv.STORAGE;
  return {
    ACTION_DB_USER,
    ACTION_DB_PASSWORD,
    DICTIONARY_PARSER_PLUGIN,
    HASURA_GRAPHQL_ADMIN_SECRET,
    LOG_FILE,
    LOG_LEVEL,
    MERLIN_GRAPHQL_URL,
    PORT,
    PLANDEV_DB,
    PLANDEV_DB_HOST,
    PLANDEV_DB_PORT,
    SEQUENCING_DB_USER,
    SEQUENCING_DB_PASSWORD,
    SEQUENCING_LANGUAGE,
    STORAGE,
  };
}
