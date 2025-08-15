[![slack](https://img.shields.io/badge/slack-aerie-brightgreen?logo=slack)](https://join.slack.com/t/nasa-ammos/shared_invite/zt-1mlgmk5c2-MgqVSyKzVRUWrXy87FNqPw)

<br>
<div align="center">
  <img alt="Aerie" height="85" src="docs/img/aerie-wordmark-with-background.svg">
</div>
<br>

Aerie is a software framework for modeling spacecraft. Its main features include:

- A Java-based mission modeling library
- A discrete-event simulator
- An embedded TypeScript DSL for defining and executing scheduling goals
- An embedded TypeScript DSL for defining and executing constraints
- An embedded TypeScript DSL for defining and executing activity command expansions
- An embedded TypeScript DSL for defining sequences
- A [GraphQL API](https://nasa-ammos.github.io/aerie-docs/api/introduction)
- A web-based [client application][ui-repo]

## Getting Started

To get started using Aerie for the first time please do our [fast track tutorial][fast-track] on our documentation website.

## Need Help?

- Join us on the [NASA-AMMOS Slack](https://join.slack.com/t/nasa-ammos/shared_invite/zt-1mlgmk5c2-MgqVSyKzVRUWrXy87FNqPw) (#aerie-users)
- Contact aerie-support@googlegroups.com

## News and Updates

- Join the [Aerie Users](https://groups.google.com/u/3/g/aerie-users) group to stay up to date on news, releases, and our project roadmap. 

## Directory Structure

```sh
.
├── .github                     # GitHub metadata
├── constraints                 # Java library for constraint checking
├── contrib                     # Java convenience classes for mission models
├── db-tests                    # Database unit tests
├── deployment                  # Deployment artifacts and documentation
├── docker                      # Additional Dockerfiles for Aerie-specific images
├── docs                        # Documentation
├── e2e-tests                   # End-to-end tests
├── examples                    # Example mission models
├── gradle                      # Gradle Wrapper
├── load-tests                  # Load testing code and configuration
├── merlin-driver               # Java library for discrete-event simulation
├── merlin-framework            # Java library for mission modeling
├── merlin-framework-junit      # Extension of JUnit to unit test mission models
├── merlin-framework-processor  # Java annotation processor for mission models
├── merlin-sdk                  # Java interface between mission models and the merlin-driver
├── merlin-server               # Service for planning and simulation
├── merlin-worker               # Worker for executing simulations
├── parsing-utilities           # Java classes for JSON serialization and deserialization
├── permissions                 # Java library for authorizing endpoint requests
├── scheduler-driver            # Java library for goal-oriented scheduling
├── scheduler-server            # Service for scheduling
├── scheduler-worker            # Worker for executing scheduling goals
├── sequencing-server           # Service for sequence generation and management
└── third-party                 # External Java dependencies that are not obtained from Maven
```

## Want to help?

Want to file a bug, contribute some code, or improve documentation? Excellent! Read up on our guidelines for [contributing][contributing]. If you are a developer you can get started quickly by reading the [developer documentation][dev].

## License

The scripts and documentation in this project are released under the [MIT License](LICENSE).

[contributing]: ./docs/CONTRIBUTING.md
[deployment]: ./deployment
[dev]: ./docs/DEVELOPER.md
[fast-track]: https://nasa-ammos.github.io/aerie-docs/introduction/#fast-track
[ui-repo]: https://github.com/NASA-AMMOS/aerie-ui

