# Bramka SMS

Lokalna bramka SMS dla telefonu z Androidem i jedną kartą SIM. Aplikacja zapisuje każde zlecenie w Room, przetwarza trwałą kolejkę jednym workerem, wysyła przez `SmsManager` i udostępnia panel WWW oraz REST API w firmowej sieci Wi‑Fi.

## Uruchomienie

1. Otwórz projekt w Android Studio z JDK 17 i Android SDK 35.
2. Zbuduj oraz podpisz APK (`Build > Generate Signed App Bundle or APK`).
3. Zainstaluj APK na telefonie, utwórz konto administratora i przyznaj uprawnienia SMS/telefon/powiadomienia.
4. Wyłącz optymalizację baterii dla aplikacji i ustaw rezerwację DHCP dla telefonu.
5. Naciśnij **Uruchom**. Adres panelu pojawi się w aplikacji i trwałym powiadomieniu.

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

Pozostałe punkty: `GET /messages`, `GET /messages/{id}`, `DELETE /messages/{id}`, `POST /messages/{id}/retry`, `GET /status` i `GET /health`. Wszystkie ścieżki poza panelem zaczynają się od `/api/v1`.

## Bezpieczeństwo i wdrożenie

- Hasła i klucze API są haszowane BCrypt; tokeny sesji SHA-256.
- Sesja panelu używa `HttpOnly` i `SameSite=Strict`, a operacje zapisujące tokenu CSRF.
- Pełny klucz API jest zwracany tylko podczas utworzenia.
- W ustawieniach można ograniczyć API do prefiksu adresu IP (np. `192.168.1.`).
- HTTP jest przewidziany tylko dla kontrolowanej sieci testowej. W produkcji należy terminować HTTPS w zaufanym lokalnym reverse proxy albo dodać certyfikat do serwera.
- Dla stabilnej pracy należy przetestować autostart i politykę oszczędzania energii na docelowym modelu telefonu.

## Świadome ograniczenia MVP

Raport dostarczenia `DELIVERED` zależy od operatora i nie jest jeszcze aktywowany. Po przerwaniu procesu wiadomość w stanie `SENDING` wraca do kolejki z adnotacją diagnostyczną; ponieważ system mógł zakończyć proces już po przekazaniu SMS do modemu, taki przypadek wymaga świadomej polityki operacyjnej, aby całkowicie wyeliminować ryzyko duplikatu.
