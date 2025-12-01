# plandev-deployment

This document describes how to deploy PlanDev.

- [Prerequisite Software](#prerequisite-software)
- [Environment Variables](#environment-variables)
- [Starting the Services](#starting-the-services)
- [Postgres Considerations](#postgres-considerations)
- [Stopping the Services](#stopping-the-services)
- [Troubleshooting](#troubleshooting)

## Prerequisite Software

Before you can deploy PlanDev, you must install and configure the following products on your deployment machine:

- [Docker](https://www.docker.com/) which is used to deploy containers for the PlanDev services.

## Setting the Environment Variables

Each container has environment variables that can be used to fine-tune your deployment.
See the [environment variable documentation](./Environment.md) for the complete set of variables.
See the example [docker-compose.yml](./docker-compose.yml) file for examples on how to set the environment variables.

Inside the [.env](./.env) there is a collection of variables that **must** be set in order to deploy PlanDev using the default [init-plandev.sh](./postgres-init-db/init-plandev.sh) and [docker-compose.yml](./docker-compose.yml).
They are, as follows:

| Name                          | Description                                                                                                                  |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `REPOSITORY_DOCKER_URL`       | The URL used to fetch images of PlanDev packages.                                                                              |
| `DOCKER_TAG`                  | The version of the PlanDev images to fetch.                                                                                    |
| `PLANDEV_USERNAME`              | The username used for PlanDev services when they access the Postgres database.                                                 |
| `PLANDEV_PASSWORD`              | The password used for PlanDev services when they access the Postgres database.                                                 |
| `HASURA_GRAPHQL_ADMIN_SECRET` | The administrator secret for Hasura. Gives complete admin access to everything when used.                                    |
| `HASURA_GRAPHQL_JWT_SECRET`   | The JWT secret for JSON web token auth.                                                                                      |
| `POSTGRES_USER`               | The username of the superuser for the Postgres database. This cannot be the same as the `PLANDEV_USERNAME` value.              |
| `POSTGRES_PASSWORD`           | The password of the superuser for the Postgres database.                                                                     |
| `UNTRUE_PLAN_START`           | Temporary solution to provide plan start time to models, should be set to a time that models will not fail to initialize on. |

### **At minimum, you _must_ assign values to the environment variables already present in [.env](./.env) in order to deploy PlanDev. Additionally, the `POSTGRES_USER` value _cannot_ be the same as the `PLANDEV_USERNAME` value.**.

## Configuring Merlin Simulation Workers

A Merlin simulation is executed by an plandev-merlin-worker. An PlanDev deployment can configure one or
more workers to provide an PlanDev deployment with the ability to execute multiple concurrent
simulations. Without a worker PlanDev will not execute any simulations. Workers are declared by adding
a worker container definition in your docker-compose file for each worker. The default docker-compose.yml
contained in this directory declares a single worker. The worker must configured to access the same
postgresql instance and database as the Merlin server. It is recommended that the number of workers
does not exceed the host's remaining available cores (considering other processes running on the host).

## Starting the Services

Download and unzip the latest [Deployment.zip](https://github.com/NASA-AMMOS/plandev/releases) file onto your local machine, then do:

```sh
cd deployment
docker-compose up --build --detach
```

## Postgres Considerations

When the Postgres container starts it will run [init-plandev.sh](./postgres-init-db/init-plandev.sh) to initialize the database with the PlanDev [database objects](./postgres-init-db/sql).

**Note:** This script is only run if you start the container with a data directory that is empty; any pre-existing database will be left untouched on container startup. See the 'Initialization scripts' section of the [Docker Postgres documentation](https://hub.docker.com/_/postgres) for more detailed information.

## Stopping the Services

```sh
docker-compose down
```

## Troubleshooting

- Make sure you are starting all the Docker containers in a clean environment. This means before starting the services make sure all containers are removed, all images are removed, and all volumes are removed.
- If you are having trouble and cannot find the answer here, please contact someone from the PlanDev team via the [#mpsa-plandev-users](https://app.slack.com/client/T024LMMEZ/C0163E42UBF) Slack channel.
