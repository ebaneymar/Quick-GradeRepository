# QuickGrade Android v1.2

QuickGrade Android wrapper with full-screen automatic camera scanning and a **MATH-a-PANG-style two-file updater**.

## Normal future updates: only 2 files

After installing the v1.2 Android shell once, normal QuickGrade UI/scanner/report changes are published with only:

1. `update-manifest.json`
2. `QuickGrade_Update.zip`

The APK does not need to be reinstalled for normal HTML/JavaScript updates. The app checks the manifest, downloads the ZIP, verifies SHA-256, installs the new runtime, and reloads itself.

### Saved data is preserved

Updates replace only the runtime `index.html`. They do **not** clear the WebView origin, localStorage, IndexedDB, quizzes, student roster, scores, scan history, or stored scan images.

## Fixed update URL

The shell checks:

`https://raw.githubusercontent.com/ebaneymar/Quick-GradeRepository/main/update-manifest.json`

The provided example manifest downloads:

`https://raw.githubusercontent.com/ebaneymar/Quick-GradeRepository/main/QuickGrade_Update.zip`

## Important

If a future update changes native Android Java code (for example camera permissions, WebView bridge code, or Android APIs), that requires a new APK shell once. After installing that shell, the same two-file runtime update system continues.

## Build APK

GitHub Actions workflow: `.github/workflows/build-apk.yml`

Build artifact: `app-debug.apk`
