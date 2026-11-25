# Documentation maintenance

Status: repository maintenance baseline, 2026-08-10.

## Directory ownership

- `docs/` is exclusively the MkDocs public user-manual source. Its direct children are locale directories.
- `development-docs/` contains internal engineering requirements, architecture baselines, and maintenance policy. It is not published by MkDocs.
- `README.md`, `ARCHITECTURE.md`, and `PRIVACY.md` remain root repository documents.

Do not reintroduce non-locale folders under `docs/`.

## Maintained languages and fallback

English (`docs/en`) and Simplified Chinese (`docs/zh`) are complete maintained baselines. Other configured locales may contain a maintained page when a reviewer can validate it; otherwise `mkdocs-static-i18n` must serve the current English page through `fallback_to_default: true`.

A stale translation is worse than a labeled fallback. When product behavior changes, update every maintained translation of that page in the same change or remove the obsolete override so fallback becomes authoritative.

The app UI ships English (`values/`) and Simplified Chinese (`values-zh/`) only. Do not reintroduce other `values-*` resource directories. The documentation site maintains English and Simplified Chinese; other MkDocs locale routes may fall back to English.

## Page-to-source map

| Manual page | Primary source of truth |
| --- | --- |
| `getting-started.md`, `about.md`, `language.md`, `appearance.md` | build configuration, About/Language/Appearance settings pages, localized resources |
| `provider.md`, `models.md`, `local-model.md` | provider registry/settings, model repository/settings, local model manager |
| `generation.md`, `context.md`, `title-generation.md` | generation/context/title settings, request builders, conversation runtime |
| `transcription.md`, `image-generation.md`, `pdf-import.md` | respective settings pages, attachment/render pipeline, request builder |
| `system-prompts.md` | prompt settings/editor, default prompt source, variable resolver |
| `tools.md`, `web-search.md`, `mcp.md`, `automation.md` | tool registry/settings, MCP repository, Task/Loop scheduling and execution |
| `shell.md`, `sandbox.md`, `proxy.md` | shell device settings/clients, flavor sandbox manager, shared HTTP client |
| `search.md`, `embedding.md`, `memory.md` | conversation-search settings, embedding repository, memory tools/storage |
| `conversations.md` | chat controller/runtime, Room graph DAO, composer and drawer UI |
| `import-export.md` | archive manifest/version, exporters/importers, auto-backup worker |
| `privacy.md`, `faq.md`, `index.md` | cross-page synthesis verified against all sources above |

Exact class names may move. Search the current settings route and user-visible string first, then follow the active implementation rather than copying a historical filename.

## Change checklist

For any behavior, default, setting, network destination, persistence, security, or supported-language change:

1. identify every affected English and Simplified Chinese page;
2. update maintained translated overrides or remove them to activate fallback;
3. update MkDocs nav and every DocumentationFab target if paths change;
4. update README/architecture/privacy when the claim is repository-level;
5. check root documents and `development-docs/` for old paths and superseded state-machine claims;
6. build docs normally and with strict warnings;
7. run an internal-link/target scan for every generated locale;
8. review the generated site route used by the app's DocumentationFab;
9. record source evidence and residual translation gaps in the task log.

## Required gates

A documentation change is not complete until:

- `git diff --check` passes;
- no conflict marker or legacy public-tree development-document path remains;
- every MkDocs nav target exists in English;
- every configured locale builds, including fallback-only locales;
- strict MkDocs emits no warning;
- documentation links resolve in the generated site;
- code-backed claims have been spot-checked against current source;
- the GitHub Pages workflow succeeds after the authorized push.

## Known failing tests

Status: observed 2026-08-22 while running `./gradlew :app:testFdroidDebugUnitTest --offline`. Updated 2026-08-29: source-contract tests were removed; see [testing.md](testing.md).

These MockK failures are **not** product regressions. They mock `ChatDao` and fail under JDK 25 because the bundled Byte Buddy does not yet support Java 25 (reports `Java 25 (69) is not supported by the current version of Byte Buddy`). They are expected to pass under JDK 17 or 21.

- `ChatContextCompactDaoTest`
- `ChatDaoRunAdmissionTest`
- `ChatDaoRunRecoveryTest`

Do not treat these as regressions. When verifying, use a JDK in the 17/21 range, or set `net.bytebuddy.experimental=true` for the test JVM.

### Localization keys

`SettingsResourceContractTest` requires `values-zh/` to carry the same string key set as default `values/`. Any new `values/strings.xml` key must be added to Simplified Chinese in the same change, or the test fails. App UI ships English and Simplified Chinese only.
