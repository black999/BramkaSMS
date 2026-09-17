# Testy odbiorcze na fizycznym telefonie

## Przygotowanie

1. Zainstaluj podpisany APK i utwórz konto administratora.
2. Przyznaj uprawnienia SMS, stanu telefonu i powiadomień.
3. Wyłącz optymalizację baterii dla aplikacji.
4. Ustaw rezerwację DHCP i ograniczenie CIDR odpowiadające firmowej sieci Wi‑Fi.
5. Włącz `Uruchamiaj po restarcie telefonu`, jeżeli autostart ma być testowany.

## Test minimum 8 godzin

Zanotuj model telefonu, wersję Androida, operatora, godzinę rozpoczęcia i zakończenia. Pozostaw usługę uruchomioną przez minimum 8 godzin, w tym przez co najmniej godzinę z wygaszonym ekranem. Co godzinę sprawdź dostępność `/api/v1/health`; na początku, w połowie i na końcu dodaj kontrolny SMS z unikalnym `Idempotency-Key`.

Wynik zalicza się, gdy usługa pozostaje dostępna, powiadomienie jest obecne, kolejka pracuje po wygaszeniu ekranu i nie powstają duplikaty. Raport powinien zawierać czasy kontroli, identyfikatory wiadomości oraz wyeksportowane logi diagnostyczne.

## Restart telefonu

1. Przy wyłączonym `startAfterBoot` uruchom telefon ponownie i potwierdź, że bramka pozostaje zatrzymana.
2. Włącz `startAfterBoot`, uruchom telefon ponownie i poczekaj na powiadomienie działającej bramki.
3. Sprawdź panel, `/api/v1/health` i wyślij kontrolny SMS.
4. Jeśli producent blokuje autostart, zanotuj komunikat z aplikacji i dodaj aplikację do wyjątków autostartu producenta.

## Odtworzenie usługi po zakończeniu procesu

1. Uruchom bramkę i potwierdź status `RUNNING` oraz dostępność `/api/v1/health`.
2. Zakończ proces aplikacji poleceniem `adb shell am kill pl.bramkasms` albo narzędziem producenta, które nie wykonuje pełnego „Wymuś zatrzymanie”. Android nie odtwarza aplikacji po operacji „Wymuś zatrzymanie” wykonanej w ustawieniach — to oczekiwane zachowanie systemu.
3. Poczekaj, aż Android odtworzy usługę `START_STICKY` i ponownie pojawi się trwałe powiadomienie.
4. Sprawdź stan `RUNNING`, `/api/v1/health`, kolejkę oraz kontrolną wysyłkę SMS.
5. Powtórz próbę z wygaszonym ekranem i zanotuj czas potrzebny do odtworzenia usługi.

Test zalicza się, jeśli usługa wraca bez ręcznego otwierania aplikacji, serwer ponownie nasłuchuje na skonfigurowanym porcie, a żadna wiadomość pozostawiona w `SENDING` nie jest automatycznie ponawiana.

## Przerwanie podczas wysyłania

Dodaj SMS, doprowadź go do `SENDING`, a następnie wymuś zatrzymanie procesu. Po ponownym uruchomieniu wiadomość musi mieć status `UNKNOWN` i nie może zostać wysłana automatycznie. Sprawdź oba działania administratora osobno: „Oznacz wysłaną” oraz „Wyślij ponownie”.

## Status wykonania

Testy wymagające fizycznego telefonu nie zostały wykonane w środowisku budowania. Ten plik jest protokołem do wypełnienia na docelowym urządzeniu; nie stanowi potwierdzenia testu.
