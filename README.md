# CDA Free Player

CDA Free Player to lekki odtwarzacz materiałów z CDA.pl, automatycznie pomijający filmy Premium. To moja własna wizja prostego odtwarzacza - zależało mi przede wszystkim na szybkości i wygodnej obsłudze, także na słabszych dekoderach i TV Boxach.

Dostępne wersje:
- Android TV / TV Box - Android 9 lub nowszy
- Windows x64
- macOS - Intel i Apple Silicon
- Linux - x64 i ARM64

Najważniejsze funkcje:
- pomijanie materiałów Premium
- ulubione
- historia i kontynuowanie oglądania
- czyszczenie historii, ulubionych i cache
- szybkie wyszukiwanie głosowe na Android TV
- opisy i komentarze
- obsługa pilotem / D-padem
- automatyczne sprawdzanie aktualizacji

## Uruchomienie

### Android TV / TV Box
Zainstaluj plik APK. Wymagany jest Android 9 lub nowszy.

### Windows
Rozpakuj całą paczkę ZIP i uruchom `CDA Free Player.exe`. mpv jest dołączone do paczki; odtwarzanie DASH korzysta z systemowego Microsoft Edge lub zainstalowanego Chrome/Brave jako lekkiego silnika multimedialnego w trybie aplikacji.

### macOS
Rozpakuj aplikację. Do DASH wymagany jest Google Chrome, Microsoft Edge albo Brave. Dla pozostałych źródeł zainstaluj mpv:

```bash
brew install mpv
```

### Linux
Na Debianie/Ubuntu po rozpakowaniu paczki uruchom raz:

```bash
./bootstrap-linux.sh
```

Następnie uruchamiaj program przez:

```bash
./run.sh
```

Na innych dystrybucjach trzeba ręcznie zainstalować Python 3 z venv i Tk, PyGObject/GTK3, WebKitGTK 4.1 oraz mpv, a następnie zależności z `requirements-linux.txt`. Do DASH potrzebny jest Chrome, Edge, Brave albo Chromium.

Program jest bardzo lekki i został zrobiony głównie z myślą o wygodnym oglądaniu CDA na telewizorze. Dla mnie jest po prostu znacznie przyjemniejszy w użyciu niż oryginalny odtwarzacz ;)

Projekt nie jest oficjalną aplikacją CDA.pl.
