# Privateer Mobile

Форк [qWDTT](https://github.com/SpaceNeuroX/proxy-turn-vk-android) (WireGuard-over-TURN
через VK-звонки, обход белых списков мобильных операторов). Лицензия — **GNU GPL v3**
(исходники открыты, как того требует лицензия оригинала).

## Что изменено относительно upstream
- Брендинг: `applicationId=zip.privateer.mobile`, имя приложения «Privateer»,
  промо-ссылки чужого форка убраны.
- CI: GitHub Actions собирает debug-APK на каждый пуш (`.github/workflows/build.yml`).

## В планах (см. privateer backend, ветка privateer-mobile)
- Онбординг в один линк из бота (схема `privateer://` → импорт подписки).
- On-connect авторизация: перед дозвоном клиент перечитывает подписку
  `/sub/mobile/<uuid>`; неактивна → отказ (мгновенный per-user отзыв).
- Гибрид VLESS↔TURN: обычное время — VLESS EU-ноды, белые списки — TURN.

## Атрибуция
Ядро туннеля (Go, DTLS/WRAP/WireGuard), Kotlin-UI и протокол — авторов qWDTT.
Privateer добавляет брендинг, серверную интеграцию подписок и авторизацию доступа.
