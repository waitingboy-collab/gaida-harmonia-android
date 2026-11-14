# Гайда Хармония (Android)

Слуша каба гайда през микрофона на телефона и в реално време свири акорди, бас и тъпан като клавирист.

Всичко се засича само: тоника, лад, темпо и размер (право хоро, ръченица, пайдушко, дайчово, копаница или бавна песен без тъпан). Акордът се сменя ~60–80 ms след новия тон; орнаментите не го местят.

- Kotlin + Jetpack Compose, minSdk 26 (Android 8.0+)
- Без интернет и без реклами; ползва само микрофона

## Билд на компютъра
Нужни: JDK 17 и Android SDK (идват с Android Studio).
```
gradlew assembleRelease
```
APK: `app/build/outputs/apk/release/app-release.apk`

## Билд в GitHub (без Android Studio)
1. Качи папката в ново репо в GitHub.
2. Всеки push в `main` прави APK → Actions → последният run → Artifacts.
3. Таг `v1.0.0` (`git tag v1.0.0 && git push --tags`) качва APK-то в Releases, откъдето хората го теглят.

## Структура
- `engine/Theory.kt` — ладове, размери, акорди
- `engine/Analyzer.kt` — височина на тона на всеки 5 ms, ручилото, начала на тоновете
- `engine/Auto.kt` — засичане на тоналност, темпо, размер и първо време
- `engine/Engine.kt` — мигновена смяна на акорда, синтез и секвенсер (същата логика като C# версията)
- `audio/AudioIO.kt` — AudioTrack/AudioRecord с родната честота и минимален буфер, декодиране на файлове
- `GaidaViewModel.kt`, `ui/GaidaApp.kt` — интерфейсът
