#!/usr/bin/env bash
#
# Публикация сборки в RuStore через их публичное API.
#
# Порядок действий по документации RuStore:
#   1. Подписать строку "keyId + timestamp" приватным ключом (RSA, SHA-512).
#   2. Получить JWE-токен: POST /public/auth/ — токен живёт 900 секунд.
#   3. Создать черновик версии, загрузить в него AAB.
#   4. По отдельному флагу отправить черновик на модерацию.
#
# Отправка на модерацию по умолчанию ВЫКЛЮЧЕНА: загрузить черновик безопасно,
# а модерация — это уже публикация для пользователей, такое не должно
# происходить случайно из-за запуска сборки.

set -euo pipefail

API="https://public-api.rustore.ru"

KEY_ID="${RUSTORE_KEY_ID:?Не задан RUSTORE_KEY_ID}"
PRIVATE_KEY="${RUSTORE_PRIVATE_KEY:?Не задан RUSTORE_PRIVATE_KEY}"
PACKAGE_NAME="${PACKAGE_NAME:?Не задан PACKAGE_NAME}"
AAB_PATH="${AAB_PATH:?Не задан AAB_PATH}"
SEND_TO_MODERATION="${SEND_TO_MODERATION:-false}"

if [[ ! -f "$AAB_PATH" ]]; then
  echo "Файл сборки не найден: $AAB_PATH" >&2
  exit 1
fi

cleanup() { rm -f private_key.pem; }
trap cleanup EXIT

# --- Ключ ---
# В консоли ключ выдаётся строкой base64 без заголовков. Разные инструменты
# оборачивают его по-разному (PKCS#8 или PKCS#1), поэтому пробуем оба варианта,
# а если ключ уже пришёл с заголовками — используем как есть.
write_key() {
  if [[ "$PRIVATE_KEY" == *"BEGIN"* ]]; then
    printf '%s\n' "$PRIVATE_KEY" > private_key.pem
  else
    printf -- "-----BEGIN PRIVATE KEY-----\n%s\n-----END PRIVATE KEY-----\n" "$PRIVATE_KEY" > private_key.pem
    if ! openssl pkey -in private_key.pem -noout >/dev/null 2>&1; then
      printf -- "-----BEGIN RSA PRIVATE KEY-----\n%s\n-----END RSA PRIVATE KEY-----\n" "$PRIVATE_KEY" > private_key.pem
    fi
  fi

  if ! openssl pkey -in private_key.pem -noout >/dev/null 2>&1; then
    echo "Не удалось прочитать приватный ключ. Проверьте секрет RUSTORE_PRIVATE_KEY." >&2
    exit 1
  fi
}

write_key

# --- Токен ---
# Формат времени важен: с миллисекундами и смещением через двоеточие (+03:00).
TIMESTAMP="$(date +"%Y-%m-%dT%H:%M:%S.%3N%:z")"
SIGNATURE="$(printf '%s' "${KEY_ID}${TIMESTAMP}" | openssl dgst -sha512 -sign private_key.pem -binary | base64 -w0)"

AUTH_RESPONSE="$(curl -sS -X POST "$API/public/auth/" \
  -H 'Content-Type: application/json' \
  -d "{\"keyId\":\"${KEY_ID}\",\"timestamp\":\"${TIMESTAMP}\",\"signature\":\"${SIGNATURE}\"}")"

TOKEN="$(echo "$AUTH_RESPONSE" | jq -r '.body.jwe // empty')"
if [[ -z "$TOKEN" ]]; then
  echo "Не удалось получить токен. Ответ RuStore:" >&2
  echo "$AUTH_RESPONSE" >&2
  exit 1
fi
echo "Токен получен."

# --- Черновик версии ---
DRAFT_RESPONSE="$(curl -sS -X POST "$API/public/v1/application/${PACKAGE_NAME}/version" \
  -H "Public-Token: ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{}')"

VERSION_ID="$(echo "$DRAFT_RESPONSE" | jq -r '.body // empty')"
if [[ -z "$VERSION_ID" || "$VERSION_ID" == "null" ]]; then
  echo "Не удалось создать черновик версии. Ответ RuStore:" >&2
  echo "$DRAFT_RESPONSE" >&2
  exit 1
fi
echo "Создан черновик версии: $VERSION_ID"

# --- Загрузка сборки ---
UPLOAD_RESPONSE="$(curl -sS -X POST "$API/public/v1/application/${PACKAGE_NAME}/version/${VERSION_ID}/aab" \
  -H "Public-Token: ${TOKEN}" \
  -F "file=@${AAB_PATH}")"

if [[ "$(echo "$UPLOAD_RESPONSE" | jq -r '.code // empty')" != "OK" ]]; then
  echo "Не удалось загрузить сборку. Ответ RuStore:" >&2
  echo "$UPLOAD_RESPONSE" >&2
  exit 1
fi
echo "Сборка загружена в черновик."

# --- Отправка на модерацию ---
if [[ "$SEND_TO_MODERATION" == "true" ]]; then
  COMMIT_RESPONSE="$(curl -sS -X POST "$API/public/v1/application/${PACKAGE_NAME}/version/${VERSION_ID}/commit" \
    -H "Public-Token: ${TOKEN}")"
  if [[ "$(echo "$COMMIT_RESPONSE" | jq -r '.code // empty')" != "OK" ]]; then
    echo "Не удалось отправить версию на модерацию. Ответ RuStore:" >&2
    echo "$COMMIT_RESPONSE" >&2
    exit 1
  fi
  echo "Версия отправлена на модерацию."
else
  echo "Черновик загружен, но НЕ отправлен на модерацию."
  echo "Проверьте его в RuStore Консоли и отправьте вручную либо запустите с send_to_moderation=true."
fi
