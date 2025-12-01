# plandev-e2e-tests

This directory contains automated end-to-end tests for PlanDev.

## Running

`AUTH_TYPE` must be set to `none` in order to run end-to-end tests.
A Docker stack with authorization disabled is available within this directory.
To spin up this stack run:

```sh
docker compose -f docker-compose-test.yml up --build
```

After starting the development Docker compose stack the following commands can be issued to run these tests:

```sh
./gradlew e2e-tests:e2eTest
```
