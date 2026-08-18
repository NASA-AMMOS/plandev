# PlanDev Project Governance

The PlanDev project aims to create an open source community that encourages transparency, contributions, and collaboration, but maintains sound technical and quality standards. Our goal is to build a community comprised of members across the space community and beyond, including from private organizations, universities, Federally Funded Research and Development Centers (FFRDCs), other government organizations, and international organizations.

The project follows a fairly liberal contribution model where people and/or organizations who do the most work will have the most influence on project direction. Technical decision making will primarily be made through a "[consensus-seeking](https://en.wikipedia.org/wiki/Consensus-seeking_decision-making)" approach.

## Roles

### User

Anyone who has downloaded, deployed, or operated the PlanDev system to meet a specific objective. PlanDev was primarily designed for space mission activity planning, modeling, and simulation, but let us know if you've found other uses for it.

### Contributor

Contributors include anyone that provides input to the project. This includes code, issues, documentation, graphics, designs, or anything else that tangibly improves the project. We encourage you to start contributing right away by joining our [Discussions](https://github.com/NASA-AMMOS/plandev/discussions) or submitting an [Issue](https://github.com/NASA-AMMOS/plandev/issues).

### Triager

Subset of contributors who have demonstrated a strong familiarity with PlanDev and are regularly contributing to the project via issue creation, commenting, discussions, etc. Triagers are given the "Triage" GitHub role and are added to corresponding GitHub team. These team members have the ability to do the following:

    - Label issues and pull requests
    - Comment, close, and reopen issues and pull requests

[List of current Triagers](https://github.com/orgs/NASA-AMMOS/teams/aerie-triagers/members)

### Collaborator

Subset of contributors who have been given write access to one or more of the PlanDev repositories. Both contributors and collaborators can propose changes to the project via pull requests, but only collaborators can formally review and approve (merge) these requests. Any contributor who has made a non-trivial contribution should be on-boarded as a collaborator in a timely manner.

If you are planning on making a substantial contribution to the project or feel as though you should be given write access to a repository, please send a request to plandev_support@jpl.nasa.gov.

[List of current collaborators](https://github.com/orgs/NASA-AMMOS/teams/aerie-collaborators/members)

## Technical Steering Committee

A subset of the collaborators forms the Technical Steering Committee (TSC). The TSC has authority over the following aspects of this project:

- Technical direction and guidance
- Committee governance and process
- Contribution policy
- Conduct guidelines
- Maintaining the list of collaborators

### TSC Committee Members

- Chris Camargo ([camargo](https://github.com/camargo)), Jet Propulsion Laboratory
- Matt Dailis ([mattdailis](https://github.com/mattdailis)), Jet Propulsion Laboratory
- Jonathan Castello ([Twisol](https://github.com/Twisol)), University of California, Santa Cruz
- Basak Alper Ramaswamy ([bashbash](https://github.com/bashbash)), Jet Propulsion Laboratory

<details>

<summary>Emeriti</summary>

### TSC Emeriti

- Pat Kenneally ([patkenneally](https://github.com/patkenneally)), Laboratory for Atmospheric and Space Physics

</details>

### Scope

The TSC is primarily responsible for the PlanDev core and UI projects:

- https://github.com/NASA-AMMOS/plandev
- https://github.com/NASA-AMMOS/plandev-ui

However, the TSC also has responsibility over some projects, which are dependencies of the core projects:

- https://github.com/NASA-AMMOS/plandev-docs
- https://github.com/NASA-AMMOS/plandev-gateway
- https://github.com/NASA-AMMOS/plandev-ts-user-code-runner
- https://github.com/NASA-AMMOS/plandev-ampcs

Addtionally, the TSC has authority over a few other "PlanDev Extended Universe" projects:

- https://github.com/NASA-AMMOS/aerie-cli
- https://github.com/NASA-AMMOS/plandev-mission-model-template
- https://github.com/NASA-AMMOS/aerie-lander

### Decision Making Process

Any community member can create a GitHub issue or comment asking the TSC to review something. Prior to implementing a substantial contribution, the design of that contribution should be reviewed by at least one member of the TSC. If consensus-seeking fails during the review of a pull request or in design discussions, the issue will be addressed by the TSC to make a determination on direction. TSC members will meet regularly and will keep track of decisions made when consensus was not met.

The TSC can nominate new members at any time. Candidates for membership tend to be collaborators who have shown great dedication to the project and/or experts in a particular domain or project component. TSC members are expected to be active contributors or members who have made significant contributions within the past 12 months.

## Project Management Committee

The Project Management Committee (PMC) is made up of sponsor organization representatives (i.e. those providing funding to the project) and key stakeholders who rely or plan to rely on PlanDev to meet a critical need (e.g. project using PlanDev for space mission operations). The PMC has the following primary responsibilities

- Maintaining the overall project roadmap
- Determining project requirements and commitments to sponsors and stakeholders
- Assessing available resources and allocating them across the project
- Monitoring and reporting on progress against the roadmap
- On-boarding new sponsors and stakeholders
- Overall project governance (including this policy)
- Addressing any legal considerations

The current list of PMC members is currently in work. If your project or organization is planning to use PlanDev and you would like to join the PMC, please contact plandev_support@jpl.nasa.gov.

### Scope

The PMC has management authority over the same projects over which the TSC has technical authority.

### Decision Making Process

The PMC will consist of a product owner and additional representative from sponsors and key stakeholders. The PMC or sponsoring organizations can nominate new members at any time. Care should be taken not to include too many members from a single stakeholder project or organization.

Monthly stakeholder meetings are held to discuss current project status and propose changes to the project roadmap. If stakeholder representatives and sponsors concur with these proposals during the meeting, they will be immediately adopted. A member of the PMC will ensure the changes have been captured and recorded. Monthly stakeholder meetings will be open to the community, but only members of the PMC have decision making authority.

Additional meetings may be held if consensus is not met or to discuss significant changes to the roadmap due to changes in stakeholder priorities or available resources. Any decision made outside of stakeholder meetings must still be approved by all sponsors and stakeholders. If full consensus cannot be made, the product owner has the final authority to determine project direction. Any non-concurrences from stakeholders or sponsors will be noted.

# Acknowledgements

Much of this governance model was adapted from the other notable open source projects including [node.js](https://github.com/nodejs/node/blob/main/GOVERNANCE.md), [OpenSSL](https://www.openssl.org/policies/omc-bylaws.html), [PostgresQL](https://www.postgresql.org/developer/), and [OpenMCT](https://github.com/nasa/openmct/blob/master/CONTRIBUTING.md)
