# Checks

The global corrections policy lives in the shared AGENTS.md. The idea is Lauren Tan's (@poteto): a mistake becomes a failing check, not another paragraph. The `correct` and `no-comments` skills are the procedures. This file only records what this project enforces.

Run `./gradlew check --continue` before opening a pull request. CI runs that same command. Use `gradle check --continue` when the wrapper is not generated yet. `--continue` prints every failing task. Gradle otherwise stops at the first one.

| Mistake | Enforced by | The message points at |
| --- | --- | --- |
| Commented-out code | `scripts/check-comments.py` on `./gradlew check --continue` | Delete it. Do not turn it back into live code |
| A banner or divider comment in Kotlin | Detekt `ForbiddenComment` | Delete the divider |
| Suppressing that banner check | Detekt `ForbiddenSuppress` | Do not suppress `ForbiddenComment`. The wording is Detekt's |
| A banner or divider comment in Java | `scripts/check-comments.py` | Delete the divider |
