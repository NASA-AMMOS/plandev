#!/usr/bin/env bash

set -e

export ADMIN_DB_USER="${PLANDEV_USERNAME}"
export ADMIN_DB_PASS="${PLANDEV_PASSWORD}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-EOSQL
  \echo 'Initializing aerie user...'
  CREATE USER "$ADMIN_DB_USER" WITH PASSWORD '$ADMIN_DB_PASS';
  \echo 'Done!'

  \echo 'Initializing gateway user...'
  CREATE USER "$GATEWAY_DB_USER" WITH PASSWORD '$GATEWAY_DB_PASSWORD';
  \echo 'Done!'

  \echo 'Initializing merlin user...'
  CREATE USER "$MERLIN_DB_USER" WITH PASSWORD '$MERLIN_DB_PASSWORD';
  \echo 'Done!'

  \echo 'Initializing scheduler user...'
  CREATE USER "$SCHEDULER_DB_USER" WITH PASSWORD '$SCHEDULER_DB_PASSWORD';
  \echo 'Done!'

  \echo 'Initializing sequencing user...'
  CREATE USER "$SEQUENCING_DB_USER" WITH PASSWORD '$SEQUENCING_DB_PASSWORD';
  \echo 'Done!'

  \echo 'Initializing "$PLANDEV_DB" database...'
  CREATE DATABASE "$PLANDEV_DB" OWNER "$ADMIN_DB_USER";
  \connect "$PLANDEV_DB"
  ALTER SCHEMA public OWNER TO "$ADMIN_DB_USER";
  \connect postgres
  \echo 'Done!'

  \echo 'Initializing "$PLANDEV_METADATA_DB" database...'
  CREATE DATABASE "$PLANDEV_METADATA_DB";
  GRANT ALL PRIVILEGES ON DATABASE "$PLANDEV_METADATA_DB" TO "$ADMIN_DB_USER";
  \echo 'Done!'
EOSQL

export PGPASSWORD="$ADMIN_DB_PASS"

psql -v ON_ERROR_STOP=1 --username "$ADMIN_DB_USER" --dbname "$PLANDEV_DB" <<-EOSQL
  \set aerie_user $ADMIN_DB_USER
  \set gateway_user $GATEWAY_DB_USER
  \set merlin_user $MERLIN_DB_USER
  \set scheduler_user $SCHEDULER_DB_USER
  \set sequencing_user $SEQUENCING_DB_USER
  \set dbName $PLANDEV_DB
  \echo 'Initializing database objects...'
  \ir /docker-entrypoint-initdb.d/sql/init.sql
  \echo 'Done!'
EOSQL
