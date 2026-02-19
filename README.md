# RegistarAppointmentSystem

ITO MGA NEED PARA MA RUN. TAKE NOTE KUNG SAKALING MAY PROBLEMS:

Android app project built with Gradle (Kotlin DSL).

## Requirements

- Android Studio (recommended)
- Android SDK Platform **API 36** installed

## Run (Android Studio)

1. Open the project folder in Android Studio.
2. Let Gradle sync finish.
3. Run on an emulator or a connected device.

## Run (command line)

On Windows:

```bash
.\gradlew.bat clean assembleDebug
```

The debug APK will be under `app/build/outputs/apk/debug/`.

## Notes

- `local.properties` is **machine-specific** (SDK path) and should not be committed or uploaded.
- Don’t upload generated folders like `.idea/`, `.gradle/`, or `**/build/` (they’re ignored by `.gitignore`).

