# Contributing to Tahoe TV

[← README](README.md) · [Bahasa Indonesia](docs/id/CONTRIBUTING.md)

Thanks for wanting to help! Tahoe TV is an open-source (MIT) community project and **anyone is welcome**: bug reports,
broken channels, translations, channels for your country, design, docs or code. You don't need to ask before starting;
comment on an issue so others know you're on it.

## Report a bug or a broken channel
1. Check the [existing issues](https://github.com/fk0u/cleanyourtv-tahoe/issues) first.
2. Grab a log from the app: **Send log** (phone: Remote · TV: Control Center · desktop: Settings → Help).
3. Open a [new issue](https://github.com/fk0u/cleanyourtv-tahoe/issues/new/choose) and pick a template. Issues in English or Indonesian are both fine.

Security problems go to [SECURITY.md](SECURITY.md), never to a public issue.

## Set up the project
- JDK 17, Android SDK (platform 36, build-tools 34+). The wrapper downloads Gradle.
- `cp secrets.example.properties secrets.properties` (optional; API keys are **never** committed).
- Tests: `./gradlew :core:test :desktop:test`
- Run the desktop app: `./gradlew :desktop:run`
- APKs: `./gradlew :launcher:assembleDebug :companion:assembleDebug`
- Website: `sh website/build.sh && python3 -m http.server -d website/_site 8080`

Code structure and flows: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Code guidelines
- **Light enough for a 1 GB TV:** the launcher uses Android Views (not Compose), no real-time blur, no heavy polling. Measure before adding a dependency.
- **`minSdk 26` (Android 8), tested on Android 9.** Avoid newer APIs without a version check (e.g. `InputStream.readNBytes` only exists from Android 13).
- Logic used by more than one app goes in `:core` (pure JVM, no Android APIs), with tests.
- UI text: short and clear. Strings are moving to resources for translation ([#56](https://github.com/fk0u/cleanyourtv-tahoe/issues/56)); new screens should use resources.
- Privacy: never log tokens, keys or personal data; URLs in logs go through `DiagLog.redact`.

## Translations
- **Docs:** copy a file into `docs/<language-code>/` (e.g. `docs/es/INSTALL.md`), translate it, link it from the README's documentation table.
- **README:** add `README.<language-code>.md` and a link in the language switcher at the top of each README.
- **Website:** add your language to the `I18N` table in `website/assets/i18n.js` (copy the `id` block as a template; missing keys fall back to English).
- **Apps:** follow [#56](https://github.com/fk0u/cleanyourtv-tahoe/issues/56) and [#71](https://github.com/fk0u/cleanyourtv-tahoe/issues/71).

## Send a pull request
1. Fork and branch from `main`.
2. One PR per change; explain *why*, not only *what*.
3. Add a line to `CHANGELOG.md` under `## Belum dirilis` for changes users will notice (English is fine).
4. Make sure the tests pass. PRs run CI and get reviewed (including an automated review).

Good places to start: [`good first issue`](https://github.com/fk0u/cleanyourtv-tahoe/labels/good%20first%20issue) · [`help wanted`](https://github.com/fk0u/cleanyourtv-tahoe/labels/help%20wanted) · [`i18n`](https://github.com/fk0u/cleanyourtv-tahoe/labels/i18n).

## Channels & sources
Tahoe only uses **free** broadcasts from public sources and hosts no streams. Don't propose paid, pirated or rights-infringing sources.
For new countries, prefer the [iptv-org](https://github.com/iptv-org/iptv) lists and fix broken streams upstream there too.

## License
By contributing you agree that your contribution is licensed under the [MIT License](LICENSE). Everyone taking part
follows the [Code of Conduct](CODE_OF_CONDUCT.md).
