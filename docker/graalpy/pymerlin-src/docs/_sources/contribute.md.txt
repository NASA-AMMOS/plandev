# Contributing

:::{note}
These guidelines are copied from [Aerie's contributing guidelines](https://github.com/NASA-AMMOS/aerie/blob/develop/docs/CONTRIBUTING.md).
:::

We would love for you to contribute to pymerlin and help make it even better than it is today! As a contributor, here are the guidelines we would like you to follow:

- [Question or Problem?](#question)
- [Building pymerlin](#building)
- [Pull Request Guidelines](#pr-guidelines)
- [Good Commit, PR, and Code Review Practices](#best-practices)
- [Submitting a Pull Request](#submit-pr)

(question)=
## Got a Question or Problem?

If you would like to chat about the question in real-time, you can reach out via [the Aerie users Slack channel](https://join.slack.com/t/nasa-ammos/shared_invite/zt-1mlgmk5c2-MgqVSyKzVRUWrXy87FNqPw).

(building)=
## Building pymerlin

To build and develop pymerlin please read through the [developer documentation](./developer.md).

(pr-guidelines)=
## Pull Request Guidelines

Here are some general Pull Request (PR) guidelines for the pymerlin project:

- Every PR should include a summary of changes that gives reviewers an idea of what they should pay attention to.
- PR branches should have as "clean" of a history as possible.
- Each commit should present one change or idea to a reviewer.
- Commits that merely "fix up" previous commits should be interactively rebased and squashed into their targets.
- Prefer the use of `git rebase` over `git merge`:

  - `git rebase` actually _rebases_ your branch from the current development branch's endpoint. This localizes conflicts to the commits at which they actually appear, though it can become complicated when there are more than a few conflicts.
  - `git merge` pulls in all the updates that your branch does not have, and combines them with the updates you have made in a single merge commit. This allows you to deal with any and all conflicts at once, but information such as when conflicts originated is lost.

  For more info on `git merge` vs `git rebase` see [here](https://www.atlassian.com/git/tutorials/merging-vs-rebasing).

- Before merging a PR, the following requirements must be met. These requirements ensure that history is effectively linear, which aids readability and makes `git bisect` more useful and easier to reason about.
  - At least one (preferably two) reviewers have approved the PR.
  - No outstanding review comments have been left unresolved.
  - The branch passes continuous integration.
  - The branch has been rebased onto the current `develop` branch.
- The "Squash and merge" and "Rebase and merge" buttons on GitHub's PR interface should not be used. Always use the "Merge" strategy.
  - In combination with the restrictions above, this ensures that features are neatly bracketed by merge commits on either side, making a clear hierarchical separation between features added to `develop` and the work that went into each feature.

(best-practices)=
## Good Commit, PR, and Code Review Practices

The pymerlin project relies on the ability to effectively query the Git history. Please read through the following resources before contributing:

- [How to write a good commit message](https://chris.beams.io/posts/git-commit/)
- [Telling Stories Through Your Commits](https://blog.mocoso.co.uk/talks/2015/01/12/telling-stories-through-your-commits/)
- [How to Make Your Code Reviewer Fall in Love with You](https://mtlynch.io/code-review-love/)

(submit-pr)=
## Submitting a Pull Request

Please follow these instructions when submitting a Pull Request:

1. Search [GitHub](https://github.com/NASA-AMMOS/pymerlin/pulls) for an open or closed PR that relates to your submission. You don't want to duplicate effort.
1. Be sure that an issue describes the problem you're fixing, or documents the design for the feature you'd like to add. Discussing the design up front helps to ensure that we're ready to accept your work.
1. Clone the [pymerlin repo](https://github.com/NASA-AMMOS/pymerlin).
1. Make your changes in a new git branch:

   ```sh
   git checkout develop
   git pull origin develop
   git checkout -b my-fix-branch develop
   ```

1. Create your patch.
1. Commit your changes using a descriptive commit message that follows our [commit conventions](#best-practices).

   ```sh
   git commit -a
   ```

   Note: the optional commit `-a` command line option will automatically "add" and "rm" edited files.

1. Push your branch to GitHub:

   ```sh
   git push origin my-fix-branch
   ```

1. In GitHub, send a pull request to `pymerlin:develop`.
1. If we suggest changes then:

- Make the required updates.
- [Rebase your branch](https://dev.to/maxwell_dev/the-git-rebase-introduction-i-wish-id-had) and force push to your branch to GitHub (this will update your Pull Request):

  ```sh
  git rebase develop -i
  git push -f
  ```

After your pull request is merged, you can safely delete your branch and pull the changes from the repository:

- Check out the develop branch:

  ```shell
  git checkout develop
  ```

- Update your develop with the latest version:

  ```shell
  git pull origin develop
  ```

- Delete the local branch:

  ```shell
  git branch -D my-fix-branch
  ```
