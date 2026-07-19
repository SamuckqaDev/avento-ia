# Contributing

## Commit messages

This repo follows [Conventional Commits](https://www.conventionalcommits.org/):
`type(scope): short summary`, lowercase, imperative mood. Common types
used here: `feat`, `fix`, `refactor`, `chore`, `docs`, `build`. Check
`git log --oneline` for examples before picking a new scope.

## Backend (Java)

- Package root is `com.avento` (the Maven `groupId` is `com.avento` too).
  Configuration property names use the `avento.*` prefix and `AVENTO_*`
  environment variables, documented in `README.md`/`docs/SETUP.md`. The
  local Postgres database/user and JPA table names also use the
  `avento`/`avento_*` naming.
- Formatting is enforced by Spotless (Palantir Java Format, 4-space
  indent). Run before committing:

  ```sh
  mvn -f back/avento/pom.xml spotless:apply
  ```

  `mvn -f back/avento/pom.xml verify` fails if the code isn't formatted (`spotless:check`
  runs in the `verify` phase).
- Tests: JUnit 5 + AssertJ. Private/package-private behavior that has
  no public seam is tested via reflection (see
  `AgentServiceDirectAutomationTest`) rather than making methods public
  just for testing.

## Frontend (TypeScript/React)

- Lint with ESLint (`front/eslint.config.js`):

  ```sh
  cd front
  npm run lint
  ```

- `npm run validate` runs typecheck + lint + build and is the gate
  before committing frontend changes.

## Editor defaults

`.editorconfig` at the repo root sets indentation (4 spaces for Java,
2 for TS/JS/JSON/YAML/CSS/HTML), UTF-8, LF line endings, and a final
newline. Most editors pick this up automatically.
