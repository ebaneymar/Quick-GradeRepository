# QuickGrade v1.4

QuickGrade for Android and personal iPhone use, with full-screen automatic camera scanning and a **MATH-a-PANG-style two-file updater**.

## Scanner and paper-saving update

Version 1.4 prints two complete answer sheets for quizzes up to 30 items on one US Letter / short-bond page, with a dashed center cut line. Each half-sheet has four registration squares, compact choice circles, and a machine-readable QuickGrade form code.

The full-screen scanner now searches the complete portrait camera frame, so a landscape half-sheet no longer has to reach the phone screen's corners. When the form is recognized, all four registration squares, the sheet outline, and the form code turn green. QuickGrade waits for three stable readings before it closes the camera and shows the grade. Sheets printed by QuickGrade 1.2 and 1.3 remain supported.

Answer sheets can be exported as Letter-size PDF, Word, or PNG. Scan one cut half-sheet at a time in landscape orientation.

On Android, exported files are saved in `Downloads/QuickGrade`. On iPhone, the native share sheet lets you choose Save to Files, Print, AirDrop, or another compatible app.

## Normal future updates: only 2 files

After installing the compatible Android or iOS shell once, normal QuickGrade UI/scanner/report changes are published with only:

1. `update-manifest.json`
2. `QuickGrade_Update.zip`

The APK or IPA does not need to be reinstalled for normal HTML/JavaScript updates. The app checks the manifest, downloads the ZIP, verifies SHA-256, installs the new runtime, and reloads itself.

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

## Personal iPhone build

The `ios/` Xcode project wraps the same offline QuickGrade runtime for iOS 15 or later. It preserves WebView local storage in a fixed Application Support location, grants the main QuickGrade page access to the iPhone camera, and supports the same two-file runtime updater.

GitHub Actions builds `QuickGrade-personal-unsigned.ipa`. The IPA intentionally has no Apple distribution signature; sign it with your own Apple ID while installing through a personal sideloading tool. Free Apple-ID signatures generally need periodic renewal. Native iOS shell changes require a new IPA, while ordinary QuickGrade runtime changes continue to use the two root update files.

For iOS compatibility, `QuickGrade_Update.zip` contains one **uncompressed** root entry named `index.html`. Its SHA-256 must match `update-manifest.json`.
