# Allenamento

Android app (Kotlin + Jetpack Compose) for running workout programs with timers, rests and audio cues,
and for recording runs with the phone's GPS (route, pace, per-km splits). The app's UI is in Italian.

It also has:

- **Diary** of workouts and runs, with effort, notes and place.
- **Morning HRV** with an Elite HRV CorSense finger sensor (Bluetooth LE), with a recovery traffic light.
- **On-device AI** (Gemini Nano) to draft programs from text or voice.
- **Health Connect** export (workouts, runs with route, HRV, resting heart rate), readable by Samsung Health.
- **Weekly backup to Google Drive**, plus Android Auto Backup.
- **DIY Bluetooth remote** to mark sets as done (see [hardware/remote](hardware/remote)).

## Build and install

```sh
export JAVA_HOME=~/.local/share/jdk-21
./gradlew installDebug        # builds and installs on the phone connected over USB
```

## JSON import format

```json
{
  "name": "Full body",
  "exercises": [
    { "name": "Tenute alla sbarra", "sets": 3, "seconds": 30, "rest": 60 },
    { "name": "Piegamenti", "sets": 3, "reps": 10, "rest": 90, "notes": "presa larga" }
  ]
}
```

- `reps` for rep-based sets, `seconds` for timed sets (one of the two).
- `rest`: rest in seconds between sets, and after the last set before the next exercise (0 = no rest).
- You can import a single program, an array of programs, or `{"programs": [...]}`.

## Privacy

See [docs/index.html](docs/index.html), published at <https://andreagrossetti.github.io/training-app/>.
