# Bramka SMS

Lokalna bramka SMS dla telefonu z Androidem i jedną kartą SIM. Aplikacja zapisuje każde zlecenie w Room, przetwarza trwałą kolejkę jednym workerem, wysyła przez `SmsManager` i udostępnia panel WWW oraz REST API w firmowej sieci Wi‑Fi.

Usługa pierwszoplanowa używa typu `remoteMessaging`, przeznaczonego przez Androida do przenoszenia wiadomości tekstowych między urządzeniami. W przeciwieństwie do `dataSync` nie podlega sześciogodzinnemu limitowi Androida 15 i może być uruchamiana po `BOOT_COMPLETED`.

## Uruchomienie

1. Otwórz projekt w Android Studio z JDK 17 i Android SDK 35.
2. Zbuduj oraz podpisz APK (`Build > Generate Signed App Bundle or APK`).
3. Zainstaluj APK na telefonie, utwórz konto administratora i przyznaj uprawnienia SMS/telefon/powiadomienia.
4. Wyłącz optymalizację baterii dla aplikacji i ustaw rezerwację DHCP dla telefonu.
5. Naciśnij **Uruchom**. Adres panelu pojawi się w aplikacji i trwałym powiadomieniu.

W panelu ustaw następnie:

- podsieć dopuszczoną do panelu i API w formacie CIDR, np. `192.168.1.0/24`;
- liczbę automatycznych ponowień oraz timeout wysyłania (domyślnie 60 sekund);
- opcjonalny autostart po restarcie telefonu;
- klucze API dla poszczególnych integracji.

Zmiana portu jest zapisywana od razu, ale wymaga zatrzymania i ponownego uruchomienia usługi.

Domyślny port to `8080`. Panel nie pobiera żadnych bibliotek ani zasobów z Internetu.

## REST API

Integracja zewnętrzna wymaga klucza utworzonego w panelu:

```http
Authorization: Bearer bms_...
Content-Type: application/json
Idempotency-Key: ZAM-326737-sms
```

```http
POST /api/v1/messages

{
  "recipient": "+48500100200",
  "content": "Towar jest gotowy do odbioru.",
  "externalId": "ZAM-326737",
  "removePolishCharacters": true
}
```

Pole `removePolishCharacters` jest opcjonalne. Jeśli nie zostanie przesłane, API użyje ustawienia `removePolishByDefault`.

Pozostałe punkty: `GET /messages`, `GET /messages/{id}`, `DELETE /messages/{id}`, `POST /messages/{id}/retry`, `GET /status` i `GET /health`. Wszystkie ścieżki poza panelem zaczynają się od `/api/v1`.

## Bezpieczeństwo i wdrożenie

- Hasła administratorów są haszowane BCrypt. Losowe klucze API oraz tokeny sesji są wyszukiwane po SHA-256.
- Migracja bazy z wersji 1 usuwa wcześniejsze klucze BCrypt, których nie da się bezpiecznie przekształcić bez znajomości pełnego klucza. Po aktualizacji należy utworzyć je ponownie.
- Sesja panelu używa `HttpOnly` i `SameSite=Strict`, a operacje zapisujące tokenu CSRF.
- Pełny klucz API jest zwracany tylko podczas utworzenia.
- W ustawieniach można ograniczyć panel i API do jednej lub kilku podsieci CIDR (np. `192.168.1.0/24`).
- HTTP jest przewidziany tylko dla kontrolowanej sieci testowej. W produkcji należy terminować HTTPS w zaufanym lokalnym reverse proxy albo dodać certyfikat do serwera.
- Dla stabilnej pracy należy przetestować autostart i politykę oszczędzania energii na docelowym modelu telefonu.

## Świadome ograniczenia MVP

Raport dostarczenia `DELIVERED` zależy od operatora i nie jest jeszcze aktywowany. Po przerwaniu procesu albo przekroczeniu timeoutu wiadomość w stanie `SENDING` przechodzi do `UNKNOWN`. Nie jest ponawiana automatycznie. Administrator musi wybrać w historii „Oznacz wysłaną” albo „Wyślij ponownie”; ta druga opcja rozpoczyna nowy pełny cykl automatycznych prób, zachowując dotychczasową historię.

Procedura testów na urządzeniu znajduje się w [docs/DEVICE_TEST_PLAN.md](docs/DEVICE_TEST_PLAN.md). Test długotrwały oraz restart muszą zostać wykonane na docelowym modelu telefonu.
