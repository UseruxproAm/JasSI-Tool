# JasSI – asystent głosowy (Android, offline)

Mikrofon → whisper.cpp (STT) → komendy (otwórz przeglądarkę / stronę / aplikację, zadzwoń, latarka, szukaj, godzina)
lub Gemma 3 1B (rozmowa) → Android TTS (po polsku).

## Zbudowanie APK (bez Androida Studio)
1. Wrzuć ten folder na GitHuba (prywatne repo wystarczy).
2. Zakładka **Actions → Build JasSI APK** (odpali się sama po pushu). Po ~10–15 min pobierz artefakt `JasSI-apk` → `app-debug.apk`.

## Zbudowanie lokalnie
`bash scripts/fetch_native.sh` → otwórz w Android Studio → Run. (JDK 17, NDK 27.2.12479018, CMake 3.22.1)

## Modele (pobierz na telefon)
- Gemma: `gemma-3-1b-it-IQ4_NL.gguf` z huggingface.co/unsloth/gemma-3-1b-it-GGUF
- Whisper (wielojęzyczny, NIE `.en`!): `ggml-base-q5_1.bin` (domyślny; `ggml-small-q5_1.bin` daje lepszy PL) z huggingface.co/ggerganov/whisper.cpp
Aplikacja sama pobiera oba modele przy pierwszym uruchomieniu na Wi-Fi (przycisk „Pobierz modele” robi to ręcznie, wznawia przerwane pobieranie).
Opcjonalnie ręcznie: przyciski „Wczytaj Gemma / Whisper” → wskaż pliki. Albo: `adb push plik /sdcard/Android/data/pl.jassi.app/files/models/`

## Przykładowe komendy
„otwórz przeglądarkę”, „otwórz stronę wp.pl”, „wejdź na youtube.com”, „otwórz ustawienia”,
„zadzwoń do mamy”, „zadzwoń do 500 123 456”, „wyszukaj pogoda Katowice”, „włącz latarkę”, „która godzina”.
Wszystko inne trafia do Gemmy.
