# QuickGrade Android

QuickGrade is an offline bubble-sheet grading app wrapped in a native Android WebView.

## Included

- QuickGrade HTML scanner embedded in `app/src/main/assets/index.html`
- A–D / A–E per-quiz choice setting
- Up to 60 questions
- Local quiz, roster, score, and scan history storage
- Captured scan images stored in WebView IndexedDB
- Camera/photo picker integration
- Android Downloads bridge for CSV, DOC, and backup exports
- GitHub Actions workflow that builds a debug APK

## Build on GitHub

Open the repository **Actions** tab, choose **Build QuickGrade APK**, and run the workflow. On success, download the `QuickGrade-debug-apk` artifact.

The workflow uses Android Gradle Plugin 8.7.x with Gradle 8.9 and JDK 17, targeting Android API 35.

## Local build

With Android SDK API 35 and Gradle 8.9 available:

```bash
gradle :app:assembleDebug
```

APK output:

`app/build/outputs/apk/debug/app-debug.apk`
