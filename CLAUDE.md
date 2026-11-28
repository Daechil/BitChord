# BitChord KO — working notes

An unofficial Korean fork of BitChord (upstream 1.10). Work happens on `ko-1.10`.

## Talking with the owner
- Reply in Korean, 해요체. Plans are welcome before larger work.
- **Never build without an explicit "빌드해".** A beta build is a push of `ko-1.10` to `beta`;
  after a build finishes, report and ask before the next step.
- New features go to beta first (A안). `main` is stable: push only a README-only commit there
  (a code change on `main` starts a stable build).

## Builds and release notes
- Pushing `beta` runs "Korean build (arm64)" and publishes `v1.10-ko.<run>-beta`; a newer push
  cancels a running build.
- Every beta needs an entry in `KO_CHANGELOG.md`: `## v1.10-ko.N-beta · date`, then `### ` sections
  (each becomes a page in the app's update window). An entry whose build never published is folded
  into the next release's notes by `.github/scripts/ko_release_notes.py`.
- A stable entry sums up the betas since the last stable (its notes never fold beta entries).
- `lyrics-check.yml` runs live probes on CI (e.g. `spotify` input; an entry `@ko-KR` searches as a
  Korean phone). CI logs are public.

## Strings
- KO strings live in `app/src/main/res/values{,-ko}/strings_ko.xml`, never in upstream's `strings.xml`.
  Add them with `python3 tools/ko_strings.py add new.json` (`[[name, english, korean], ...]`).
- Korean copy is 해요체 (`KoreanToneTest` fails on ~합니다).
- Before merging a new upstream: `python3 tools/ko_strings.py check-upstream <ref>`.

## Code
- `BitChordApp` in `MainActivity.kt` is at the JVM method-size limit: even inline list helpers
  (`filter`, `indexOfFirst`) inside it break the build. Put logic in helper objects and call them.
  A "Method too large" there after changing only `shared` can be the incremental cache: clear
  `app/build/kotlin` and `app/build/tmp/kotlin-classes` and compile again before believing it.
- Tests: `./gradlew :app:testDevDebugUnitTest :shared:jvmTest :sharedUi:jvmTest --offline`
  — all of them pass; a failure is a real problem.

## Safety
- Never log or print API keys or lyrics; keys stay out of exports and backups.
- Nothing private in CI inputs (public logs): no personal playlist contents or Spotify ids.
- Never ask for secrets in chat.

## Working economically
- Read only the lines needed (`grep -n`, `sed -n a,bp`), batch commands, and wait on CI with one
  background command rather than repeated checks.
