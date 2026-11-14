# Contributing

## Building

The repository tracks source and the Gradle wrapper. Build output, the local `run/` server directory, private backups
and secrets are ignored.

```bash
./gradlew build                 # → build/libs/VoiceWarden-<version>.jar
./gradlew runServer             # local test server, on the Paper version in gradle.properties
./gradlew test -PsherpaIntegration=/path/to/models   # optional end-to-end test with the real engine
```

## Paper versions and CI

`paperApi` in `gradle.properties` is the oldest supported Paper version, and the plugin compiles against it. To build
against another version, pass it with `-PpaperApi`, for example `./gradlew build -PpaperApi=26.3.+`. CI tests the
newest Paper build of each version in the matrix in `.github/workflows/build.yml`. To support a new version, add it to
the matrix. To drop the oldest, change `paperApi` and the first matrix entry.

## Speech integration test

To replay recorded speech through an installed model:

```bash
./gradlew test \
  -PsherpaIntegration=run/plugins/VoiceWarden/models \
  -PsherpaNatives=run/plugins/VoiceWarden/natives/sherpa-onnx-1.13.8/osx-aarch64 \
  -PsherpaModel=whisper-turbo \
  -PsherpaRecordings=run/plugins/VoiceWarden/recordings
```

Use your platform's native-library directory in place of `osx-aarch64`, and 16 kHz mono PCM WAVs. Use
`-PsherpaModel=whisper-small-en` for Small, or omit it for Parakeet.

Audio fixtures and recordings are supplied locally and are not included in the repository. Audio files are ignored
by Git, and CI rejects tracked files covered by `.gitignore`.

## Releasing

Between releases, `main` carries the next version with a `-SNAPSHOT` suffix, for example `2.0.0-SNAPSHOT`. Follow
semantic versioning when choosing it: a major bump when upgrading needs operator action or loses data, minor for new
features, patch for fixes only. To release, remove the suffix in a normal pull request (adjusting the number if the
changes since the last release call for it). Once it has merged, tag that commit on `main` with `v<version>` and push
the tag:

```
git tag v1.0.0 && git push origin v1.0.0
```

The Release workflow checks that the tag matches the Gradle version, runs `./gradlew build`, and publishes a GitHub
release with the JAR, a SHA-256 checksum file and notes generated from the merged pull requests. You can edit the notes
afterwards. Then set `version` to the next `-SNAPSHOT` in a follow-up pull request.

## Pull requests

- Branch off `main` and open the pull request against `main`.
- `./gradlew build` must pass.
- The required check is **Build and test**.
- Use a conventional-commit title in plain language, for example `fix(voice): muted players no longer reach the queue`.
  The description states the problem, then the fix.

For how the plugin fits together, see [docs/architecture.md](docs/architecture.md).
