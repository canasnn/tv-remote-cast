# Contributing to TV Remote & Cast

Contributions are welcome.

1. Fork https://github.com/canasnn/tv-remote-cast and clone your fork.
2. Open the project in Android Studio (JDK 21) and build the debug variants:
   ```bash
   ./gradlew :app:assembleDebug :receiver:assembleDebug
   ```
3. Create a branch: `git checkout -b feature/your-feature`.
4. Keep the style of the surrounding code (Kotlin, Jetpack Compose, Material 3; theme colours only so dark mode stays readable).
5. Logic that doesn't need Android goes into `castcore` with unit tests:
   ```bash
   ./gradlew :castcore:test :protocol:test
   ```
6. Regexes run on Android's ICU engine: escape `]` and `}` outside character classes.
7. The app stays free: no ads, paywalls, billing or tracking libraries.
8. Never commit secrets: keystores, API keys, `local.properties`.
9. Test on a phone and an Android TV (pairing, remote keys, casting) and open a pull request describing what you changed and how you tested it.

By contributing you agree that your contribution is licensed under the project's license (Apache 2.0; GPL-3.0 for the receiver module).
