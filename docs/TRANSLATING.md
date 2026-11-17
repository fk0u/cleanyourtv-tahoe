# Translating Tahoe TV

Tahoe TV speaks English, Indonesian, Chinese and Arabic (right-to-left) in full; Arabic is the first language kept
as a community JSON file. Adding or improving a language never requires editing the 3 000-line `I18n.kt`.

## Improve an existing community language (e.g. Arabic)

1. Open `core/src/main/resources/i18n/<lang>.json` (e.g. `ar.json`). It maps keys to text.
2. Find the English text for a key in `core/src/main/kotlin/tech/kousozo/tahoetv/core/I18n.kt` (`private val EN`).
3. Add or fix entries. Keys you leave out fall back to English automatically.
4. Keep every placeholder: `%s` (text) and `%d` (number) must appear the same number of times as in English.
5. Run `./gradlew :core:test`. `I18nTest` rejects unknown keys, blank text and mismatched placeholders.

## Add a new language

| Where | What to add |
| --- | --- |
| `core/.../Region.kt` | the ISO 639-1 code in `LANGUAGES` (and in `RTL` if it is written right-to-left) |
| `core/src/main/resources/i18n/<lang>.json` | app strings (start with the `nav.*`, `home.*`, `live.*` keys) |
| `core/.../OnboardingText.kt` | the name in `LANGUAGE_NAMES` (in its own script) and a full onboarding table |
| `core/.../Brand.kt` | `tagline(lang)` |
| `website/assets/i18n.js` | a block for the site, a `data-lang` button in `website/index.html`, and the code in `I18N_RTL` if RTL |
| `README.<lang>.md` (optional) | a translated README linked from the language bar in `README.md` |

The apps pick the new language up automatically: the TV launcher, phone and desktop onboarding list every entry of
`Region.LANGUAGES`. Right-to-left languages mirror the layout (`Region.isRtl`), except video, seek bars and
transport controls, which always run left to right.

## Style

- Short and calm, like the English copy. Say "channel", not "stream", in your language's everyday word.
- Product name stays **Tahoe TV**; never translate or split it.
- Numbers use Western digits (`%d` is formatted with `Locale.US`) so channel numbers and times read the same everywhere.

## Channels in your language

Besides countries, viewers can add *extra channel languages* (Settings → Extra channel languages), loaded from
iptv-org's `languages/<iso639-3>.m3u`. To support a new one, add its ISO 639-1 → 639-3 pair to `Region.ISO639_3`.
