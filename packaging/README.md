# Package managers

One command to install Tahoe TV on a desktop. `update-manifests.sh vX.Y.Z` fills the version and the SHA-256 of each
installer from the GitHub release, writing ready-to-submit files into `packaging/out/`:

* **Homebrew cask** `tahoe-tv.rb` → submit to a tap (`brew install --cask fk0u/tap/tahoe-tv`) or to `homebrew/cask`.
* **winget** three manifests → submit to `microsoft/winget-pkgs` (`winget install fk0u.TahoeTV`). winget wants a signed MSI,
  so do this after the Windows signing secrets are set (see below).

## Signing secrets (GitHub → Settings → Secrets, environment `release`)

| Platform | Secrets |
|---|---|
| macOS | `MACOS_CERT_P12_B64` (Developer ID Application cert as base64 .p12), `MACOS_CERT_PASSWORD`, `MACOS_SIGN_IDENTITY` (`Developer ID Application: Name (TEAMID)`), `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD` (app-specific password) |
| Windows | `MSI_CERT_PFX_B64` (code-signing cert as base64 .pfx), `MSI_CERT_PASSWORD` |

Without them the workflow still builds, just unsigned. Signing runs on version tags only.
