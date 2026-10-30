# LifeAssistant

**LifeAssistant** — Android-приложение персонального помощника. Оно объединяет бюджет и учёт расходов, поиск мест на карте, голосовое управление и помощника, который может выполнять действия в функциях приложения.

Проект продолжает кодовую базу LifeNavigator и включает голосовой интерфейс, ранее развивавшийся в отдельном SoulAndroid. Идентификатор Android-приложения намеренно сохранён как `com.lifenavigator.app`, чтобы новые APK обновляли существующую установку.

## Текущее состояние

- **Бюджет:** локальное хранение доходов, расходов, категорий и лимитов; расчёты и уведомления о превышении.
- **Банковские операции:** приём поддерживаемых банковских уведомлений и SMS после явного предоставления системных разрешений; разбор операций и очередь подтверждения.
- **Карта:** Yandex MapKit, поиск организаций по категориям, фильтры, карточки мест, сохранение последнего результата и переходы в картографические приложения.
- **Голосовой помощник:** русское распознавание речи Android; распознанная команда отправляется автоматически, ответы могут озвучиваться через Android TextToSpeech.
- **Автономный режим помощника:** расчёт расходов и лимитов, ответы по сохранённым местам. Это локальный обработчик известных команд, а не отдельная локальная LLM.
- **Облачный помощник:** серверный маршрут YandexGPT реализован. Для его работы нужно развернуть backend, настроить его переменные окружения и собрать APK с `BACKEND_BASE_URL`.
- **Подписки:** экран пока использует демонстрационный источник данных; автоматическое обнаружение реальных подписок и автоплатежей ещё требует доработки.
- **S.O.U.L.:** долговременная память, характер агента и локальная языковая модель из исходного SoulAndroid пока не реализованы; в SoulAndroid они были каркасами интерфейсов.

## Структура репозитория

```text
app/                         Android-приложение (Kotlin, Jetpack Compose)
server/                      Node.js/Express backend и SQLite
scripts/                     Сборка подписи и публикация в RuStore
.github/workflows/
  android-build.yml          CI и debug APK через GitHub Release
  cleanup-build-history.yml  Очистка истории сборок и debug-релизов
  release-rustore.yml        Подписанная сборка и публикация в RuStore
docs/
  BUILD_AND_RETENTION.md     Инструкция по CI, Releases и срокам хранения
  archive/                   Историческая документация раннего MVP
```

## Локальная Android-сборка

Нужны JDK 17, Android SDK 35 и Gradle 8.7.

В PowerShell из корня проекта:

```powershell
Copy-Item local.properties.example local.properties
```

Заполните в `local.properties` значения, которые нужны мобильному приложению: прежде всего ограниченный `MAPKIT_API_KEY`, OAuth client IDs/redirect URI и при необходимости HTTPS-адрес backend в `BACKEND_BASE_URL`.

Соберите debug APK:

```powershell
gradle assembleDebug
```

Файл появится здесь:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Не добавляйте `local.properties` в Git. Всё, что попадает в APK через BuildConfig, ресурсы или манифест, можно извлечь из APK. Поэтому в Android-проекте не должно быть серверных API-ключей, паролей подписи или приватных ключей.

## Сборки GitHub Actions и GitHub Releases

**Android CI** запускается на push и pull request в `main`: проверяет синтаксис backend, собирает debug APK и прикладывает artifact к запуску Actions.

Чтобы создать APK как GitHub Release, откройте **Actions → Android CI → Run workflow** и запустите workflow вручную. После успешной сборки будет создан prerelease с тегом `debug-N` и APK во вложении. Обычные CI-запуски не создают Releases.

Подробности и политика хранения: [docs/BUILD_AND_RETENTION.md](docs/BUILD_AND_RETENTION.md).

## Backend и облачный помощник

Backend использует Node.js, Express и SQLite. Он содержит маршруты входа через Яндекс ID/VK ID, согласия, профиля, бюджета и проксирования запросов к YandexGPT.

Для развёртывания:

1. Скопируйте `server/.env.example` в `server/.env` на сервере.
2. Задайте `JWT_SECRET`, `YANDEX_GPT_API_KEY` и `YANDEX_GPT_FOLDER_ID` только в окружении backend.
3. Запустите `docker compose up -d --build`.
4. Настройте публичный HTTPS reverse proxy и проверьте `/health`.
5. Сохраните HTTPS URL backend в GitHub Actions Secret `BACKEND_BASE_URL` и запустите новую сборку приложения.
6. В приложении выполните вход через Яндекс ID или VK ID для создания серверной сессии.

Полная инструкция: [server/DEPLOYMENT.md](server/DEPLOYMENT.md).

## Секреты и приватные данные

- Не коммитьте `local.properties`, `server/.env`, release keystore, пароли, приватные ключи RuStore или API-ключи.
- Для CI используйте GitHub Actions Secrets. Временные файлы сборки удаляются с runner после выполнения.
- `YANDEX_GPT_API_KEY`, `JWT_SECRET` и ключи других серверных API должны храниться только на backend.
- Ограничивайте мобильный MapKit key настройками провайдера: он необходим на устройстве и поэтому извлекаем из APK.
- Перевод репозитория в private ограничивает доступ к оригиналу, но не удаляет копии, которые могли быть скачаны, пока репозиторий был публичным. Публичные forks также не становятся private автоматически.

## RuStore

Для подписанной сборки и публикации используйте отдельный workflow **Release for RuStore**. Он собирает release AAB/APK, проверяет подпись и по выбранным параметрам может загрузить пакет в RuStore. Секреты подписи и API RuStore хранятся в GitHub Actions Secrets.

