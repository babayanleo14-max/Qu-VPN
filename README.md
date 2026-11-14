# Qu VPN — Android

Android-приложение для Qu VPN на базе API `api.open60m.ru`.

## Структура проекта

```
app/src/main/java/ru/open60m/quvpn/
├── api/
│   └── ApiClient.kt          # HTTP-клиент к API (аналог background.js apiRequest)
├── model/
│   └── Models.kt             # Модели данных (Session, Server, VpnState)
├── service/
│   └── QuVpnService.kt       # VpnService — TUN-интерфейс + туннель через прокси
└── ui/
    ├── MainActivity.kt       # Экран приложения
    └── MainViewModel.kt      # Логика (аналог background.js doEnable/doDisable)
```

## Как собрать

1. Открой папку `quvpn` в **Android Studio** (File → Open)
2. Дождись синхронизации Gradle
3. Подключи телефон или запусти эмулятор
4. Run → Run 'app'

Минимальный Android: **8.0 (API 26)**

## Как работает туннель

```
Телефон → TUN (Android VpnService) → QuVpnService → HTTP прокси (open60m нода) → Интернет
```

1. `MainViewModel.connect()` → `ApiClient.createSession()` → получаем `proxy.host:port`
2. `QuVpnService` создаёт TUN-интерфейс (`Builder.establish()`)
3. Весь трафик телефона идёт в TUN
4. `QuVpnService` форвардит пакеты через сокет к прокси-серверу
5. Прокси-сокет защищён `protect()` — он идёт напрямую, минуя VPN (иначе петля)

## Важные замечания

### Туннель
Текущая реализация — прямой форвардинг TCP пакетов через HTTP-прокси.
Для продакшена рекомендуется **tun2socks** (C-библиотека):
- [tun2socks GitHub](https://github.com/xjasonlyu/tun2socks)
- Поддерживает UDP, DNS, лучше работает с мобильными приложениями

### Добавить tun2socks
```kotlin
// В QuVpnService вместо ручного форвардинга:
// 1. Скомпилируй tun2socks как .aar или используй Go mobile
// 2. Запусти: tun2socks -device tun://fd -proxy socks5://host:port
```

## API endpoints используемые приложением

| Endpoint | Метод | Описание |
|----------|-------|----------|
| `/session/create` | POST | Создать сессию, получить прокси |
| `/session/destroy` | POST | Уничтожить сессию (с токеном) |
| `/premium/status` | GET | Проверить Premium |

## install_id

Аналогично расширению — UUID генерируется при первом запуске и сохраняется в `SharedPreferences`.
