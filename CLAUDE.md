# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Aerie is a software framework for spacecraft mission modeling and simulation. It provides:
- A Java-based discrete-event simulator (merlin-driver)
- Mission modeling libraries (merlin-framework, merlin-sdk)
- TypeScript DSLs for constraints, scheduling goals, command expansions, and sequences
- A GraphQL API with Hasura
- Microservices architecture with separate workers for simulation, scheduling, and sequencing

## Build and Test Commands

### Building
```bash
# Build all modules
./gradlew assemble

# Build a specific module
./gradlew :merlin-driver:assemble
./gradlew :merlin-server:assemble
```

### Testing
```bash
# Run all unit tests
./gradlew test

# Run tests for a specific module
./gradlew :merlin-driver:test

# Run a single test class (example)
./gradlew :merlin-driver:test --tests "gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngineTest"

# Run end-to-end tests (requires Docker stack)
./gradlew e2e-tests:e2eTest

# Run database tests
./gradlew db-tests:e2eTest
```

### Docker Development
```bash
# Set up environment (first time only)
cp .env.template .env
# Edit .env with appropriate values

# Start all services
docker-compose up --build --detach

# Stop all services
docker compose down

# Remove volumes (clean database)
docker volume prune

# View logs for a specific service
docker logs -f aerie_merlin
docker logs -f aerie_scheduler

# Enter a container
docker exec -it aerie_merlin /bin/bash
docker exec -it aerie-postgres /bin/sh
```

### TypeScript DSL Compilers
```bash
# Build constraints DSL compiler
cd merlin-server/constraints-dsl-compiler
npm run build

# Build scheduling DSL compiler
cd scheduler-worker/scheduling-dsl-compiler
npm run build

# Generate documentation
npm run generate-doc
```

### Other Useful Commands
```bash
# Check for dependency updates
./gradlew dependencyUpdates

# Generate procedural API documentation
./gradlew dokkaHtmlMultiModule
# View at procedural/build/dokka/htmlMultiModule/index.html
```

## Architecture

### Core Simulation Stack

**merlin-sdk** → **merlin-framework** → **merlin-driver**

- **merlin-sdk**: Defines the interface between mission models and the simulation engine. Contains core abstractions like `Activity`, `Resource`, and model lifecycle hooks.
- **merlin-framework**: Provides implementation utilities for building mission models. Users write mission models using this framework (see `examples/banananation`).
- **merlin-driver**: The discrete-event simulation engine. Key class: `SimulationEngine` (`merlin-driver/src/main/java/gov/nasa/jpl/aerie/merlin/driver/engine/SimulationEngine.java`).

### Mission Model Structure

Mission models are packaged as JAR files and dynamically loaded. Key characteristics:
- Must include all dependencies in the JAR (fat JAR) except `merlin-sdk`
- Use annotation processing via `merlin-framework-processor`
- Example: `examples/banananation` shows a complete mission model with activities and resources
- Mission models define spacecraft activities (e.g., "TurnOn", "Charge") and resources (e.g., "battery level", "data rate")

### Service Architecture

The system follows a server-worker pattern:

**Servers** (HTTP/GraphQL endpoints):
- **merlin-server**: Mission model management, plan creation, simulation orchestration (port 27183)
- **scheduler-server**: Scheduling request orchestration (port 27185)
- **sequencing-server**: Sequence generation and management (port 27184)
- **action-server**: Command expansion execution (port 27186)

**Workers** (computation engines):
- **merlin-worker**: Executes simulations by invoking merlin-driver
- **scheduler-worker**: Executes scheduling goals using scheduler-driver
- Workers pull tasks from the database and update results

**Gateway & Database**:
- **aerie_gateway**: Authentication and routing layer (port 9000)
- **hasura**: GraphQL API gateway (port 8080)
- **postgres**: Central database for all services

### TypeScript DSL Compilation

TypeScript DSLs are compiled to JavaScript and executed in a sandboxed environment:

1. **constraints-dsl-compiler** (in merlin-server): Compiles constraint checking logic
2. **scheduling-dsl-compiler** (in scheduler-worker): Compiles scheduling goals
3. Both use `@nasa-jpl/aerie-ts-user-code-runner` for sandboxed execution
4. DSL code is written by users in TypeScript and compiled on-demand

### Procedural Libraries

Post-simulation analysis libraries in Kotlin:
- **procedural:timeline**: Timeline manipulation and queries
- **procedural:constraints**: Constraint evaluation
- **procedural:scheduling**: Scheduling utilities
- Located in `procedural/` directory with unified documentation via Dokka

### Supporting Libraries

- **parsing-utilities**: JSON serialization/deserialization for value schemas
- **permissions**: Authorization checks for API endpoints
- **contrib**: Convenience classes for mission modelers
- **type-utils**: Type system utilities shared across modules
- **orchestration-utils**: Test utilities for orchestrating services

## Development Workflow

### Working on the Simulation Engine (merlin-driver)

When modifying simulation logic:
1. Make changes in `merlin-driver/src/main/java`
2. Run unit tests: `./gradlew :merlin-driver:test`
3. Test with example model: Use `merlin-framework-junit` test utilities
4. Integration test: Build and run Docker stack with test mission model

### Working on Mission Models

Mission models are in `examples/`:
1. Modify activity or resource definitions
2. Run model-specific tests: `./gradlew :examples:banananation:test`
3. Build JAR: `./gradlew :examples:banananation:jar`
4. Upload to running Aerie instance for integration testing

### Working on TypeScript DSLs

When modifying constraint or scheduling DSL:
1. Make changes in `merlin-server/constraints-dsl-compiler/src` or `scheduler-worker/scheduling-dsl-compiler/src`
2. Run `npm run build` in the appropriate directory
3. Test compilation with `npm run test`
4. Rebuild Docker container to test end-to-end

### Working on Services

When modifying merlin-server, scheduler-server, etc.:
1. Make changes in Java source
2. Rebuild: `./gradlew :merlin-server:assemble`
3. Rebuild Docker: `docker-compose up --build aerie_merlin`
4. Check logs: `docker logs -f aerie_merlin`

### Running E2E Tests

E2E tests require a running Docker stack with `AUTH_TYPE=none`:
```bash
# Start test stack
docker compose -f e2e-tests/docker-compose-test.yml up --build

# Run tests
./gradlew e2e-tests:e2eTest
```

## Git Workflow

- **Main branch for PRs**: `develop` (not `main`)
- **Commit strategy**: Use "Merge" button only (not "Squash and merge" or "Rebase and merge")
- **Rebase before merging**: Always rebase onto `develop` before merging
- PRs require at least one approval and passing CI
- Follow commit message conventions from [How to write a good commit message](https://chris.beams.io/posts/git-commit/)

### Creating PRs with Dependencies

For PRs that depend on other in-flight PRs:
1. Add `"publish"` label to dependency PRs (creates `pr-XXXX` Docker images)
2. In dependent PR body, specify:
   ```
   ___REQUIRES_AERIE_PR___="9999"
   ___REQUIRES_GATEWAY_PR___="9999"
   ```

## Key File Locations

### Simulation Engine
- Core engine: `merlin-driver/src/main/java/gov/nasa/jpl/aerie/merlin/driver/engine/SimulationEngine.java`
- Simulation driver: `merlin-driver/src/main/java/gov/nasa/jpl/aerie/merlin/driver/SimulationDriver.java`
- Resource management: `merlin-driver/src/main/java/gov/nasa/jpl/aerie/merlin/driver/resources/`

### Mission Model Framework
- Framework base: `merlin-framework/src/main/java/gov/nasa/jpl/aerie/merlin/framework/`
- Annotation processor: `merlin-framework-processor/src/main/java/`
- Example models: `examples/banananation/src/main/java/gov/nasa/jpl/aerie/banananation/`

### Services
- Merlin server: `merlin-server/src/main/java/gov/nasa/jpl/aerie/merlin/server/`
- Scheduler server: `scheduler-server/src/main/java/gov/nasa/jpl/aerie/scheduler/server/`
- Sequencing server: `sequencing-server/src/`

### Configuration
- Docker composition: `docker-compose.yml`
- Environment template: `.env.template`
- Gradle settings: `settings.gradle`, `build.gradle`

## Important Notes

- **Java version**: OpenJDK 21 (Temurin LTS)
- **Gradle wrapper**: Use `./gradlew` (not system Gradle)
- **Docker platform**: For Apple Silicon, may need `DOCKER_DEFAULT_PLATFORM=linux/arm64` and `DOCKER_BUILDKIT=0`
- **Postgres**: Use Docker container, not local Postgres service (would clash on port 5432)
- **File store**: Mission model JARs and simulation data stored in Docker volumes (`aerie_file_store`)
- **Debug ports**: Services expose debug ports (merlin: 5005, scheduler: 5006) for remote debugging

## Common Patterns

### Adding a New Activity Parameter

1. Add parameter to activity class in mission model (e.g., `examples/banananation`)
2. Update annotation if needed (`@Export.Parameter`)
3. Rebuild mission model JAR
4. Upload new version to Aerie

### Adding a New Resource

1. Define resource in mission model's `Configuration` or as cell
2. Export via `@Export.Resource`
3. Ensure resource dynamics are properly modeled
4. Test with `merlin-framework-junit`

### Modifying the Simulation Engine

1. Update `merlin-driver` code
2. Run `./gradlew :merlin-driver:test`
3. Test with integration tests using `merlin-driver-test`
4. Rebuild and test with full Docker stack

### Adding Database Migrations

Database schema is managed by Hasura:
1. Migrations are in separate `aerie-gateway` repository
2. For development, schema changes go through Hasura console (port 8080)
3. Coordinate with gateway team for production migrations
