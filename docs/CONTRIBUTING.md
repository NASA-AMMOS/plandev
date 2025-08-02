# Contributing

We would love for you to contribute to Aerie and help make it even better than it is today! As a contributor, here are the guidelines we would like you to follow:

- [Question or Problem?](#question)
- [Building Aerie](#building)
- [Pull Request Guidelines](#pr-guidelines)
- [Good Commit, PR, and Code Review Practices](#best-practices)
- [Submitting a Pull Request](#submit-pr)

## <a name="question"></a> Got a Question or Problem?

If you would like to chat about the question in real-time, you can reach out via [our Slack channel](https://join.slack.com/t/nasa-ammos/shared_invite/zt-1mlgmk5c2-MgqVSyKzVRUWrXy87FNqPw).

## <a name="building"></a> Building Aerie

To build and develop Aerie please read through the [developer documentation](./DEVELOPER.md).

## <a name="pr-guidelines"></a> Pull Request Guidelines

Here are some general Pull Request (PR) guidelines for the Aerie project:

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

## <a name="best-practices"></a> Good Commit, PR, and Code Review Practices

The Aerie project relies on the ability to effectively query the Git history. Please read through the following resources before contributing:

- [How to write a good commit message](https://chris.beams.io/posts/git-commit/)
- [Telling Stories Through Your Commits](https://blog.mocoso.co.uk/talks/2015/01/12/telling-stories-through-your-commits/)
- [How to Make Your Code Reviewer Fall in Love with You](https://mtlynch.io/code-review-love/)

## <a name="submit-pr"></a> Submitting a Pull Request

Please follow these instructions when submitting a Pull Request:

1. Search [GitHub](https://github.com/NASA-AMMOS/aerie/pulls) for an open or closed PR that relates to your submission. You don't want to duplicate effort.
1. Be sure that an issue describes the problem you're fixing, or documents the design for the feature you'd like to add. Discussing the design up front helps to ensure that we're ready to accept your work.
1. Clone the [Aerie repo](https://github.com/NASA-AMMOS/aerie).
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

1. In GitHub, send a pull request to `aerie:develop`.
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

## <a name="submit-pr"></a> Required Checks on PRs

When you submit a PR, we use Github Workflows to automatically run a number of CI-type checks on it, all of which must pass before the PR can be merged. These vary slightly from one repo to another, but mostly consist of unit tests, as well as end-to-end (e2e) tests for the backend and frontend (`aerie` and `aerie-ui`) repos. If your PR adds new tests to our existing test suites, they will automatically be run & validated with your PR.

You can see the status of these checks at the bottom of the PR on Github - it should say "All checks have passed" if all is well. If it says "Some checks are not successful", you'll need to troubleshoot the cause of the failure before your PR can be accepted. Click "show all checks", then "details" to get detailed server logs from every step of the GH action that failed. Note that some of these runs may also have *files attached* to the run, which contain more detailed logs.

If you can't determine the cause of the failure from logs alone, it may be helpful to try and reproduce the problem locally. You can see the commands run by the Required Check actions by looking in each repo under `.github/workflows` - each `yml` file in this folder contains the setup & commands necessary to run the check locally yourself.

## <a name="submit-pr"></a> Running E2E Tests with specific branches/images

Both `aerie` and `aerie-ui` repos contain **end-to-end (E2E) tests** which depend on code from other Aerie repos. Specifically:
- The `aerie` backend e2e test suite, which tests a fully running Aerie API, depends on `aerie-gateway` since it is part of the API
- The `aerie-ui` e2e suite, which tests a full Aerie UI + backend, depends on `aerie` and `aerie-gateway` code since it is a full stack test

Sometimes a new feature requires code changes in *multiple repos simultaneously*, eg. a UI PR may add a new UI element that displays a new database field, but relies on a corresponding backend PR to add the new field. In this case, the UI PR's e2e tests will fail unless it has a way to *specify* which backend PR(s)/branches should be used for the e2e test.

To handle this situation, follow these steps with your PRs:
1. When you submit an `aerie` or `aerie-gateway` PR, if your PR contains code that *other PRs depend on*, add the GH label **"publish"** to the PR. This will cause an additional workflow to run which *publishes* the code in that branch to a docker image called eg. `pr-9999` for use by other PRs.
2. When you submit an `aerie-ui` or `aerie` PR, if it *depends on other PR(s)* (aerie or gateway) for its tests to pass, edit the **body of your PR** to contain one or both of the following lines:
```
___REQUIRES_AERIE_PR___="9999"
___REQUIRES_GATEWAY_PR___="9999"
```
Replace "9999" with the **PR number(s)** of the relevant `aerie`/`gateway` PR(s). PRs to `aerie-ui` allow either/both of these fields, while `aerie` PRs only allow a `gateway` PR to be specified.

For example, see [this PR](https://github.com/NASA-AMMOS/aerie-ui/pull/1420) or [this PR](https://github.com/NASA-AMMOS/aerie-ui/pull/1492).
