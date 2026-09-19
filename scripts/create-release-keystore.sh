#!/usr/bin/env bash
#
# То же самое для Linux/macOS:
#   bash scripts/create-release-keystore.sh
#
# Ключ остаётся только у вас: тот, у кого есть этот файл и пароли,
# может выпустить обновление вашего приложения от вашего имени.

set -euo pipefail

KEYSTORE="release.keystore"
ALIAS="lifenavigator"

if [[ -f "$KEYSTORE" ]]; then
  echo "Файл $KEYSTORE уже существует."
  echo "Если создать новый ключ поверх старого, обновления приложения перестанут ставиться."
  exit 1
fi

read -r -s -p "Придумайте пароль ХРАНИЛИЩА (минимум 6 символов): " STORE_PASS; echo
read -r -s -p "Придумайте пароль КЛЮЧА (можно тот же): " KEY_PASS; echo

keytool -genkeypair -v \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS" \
  -dname "CN=LifeNavigator, OU=Dev, O=LifeNavigator, L=NA, S=NA, C=RU"

base64 -w0 "$KEYSTORE" > release.keystore.base64.txt

cat <<TEXT

Готово. Создайте четыре секрета в репозитории:
  Settings -> Secrets and variables -> Actions -> New repository secret

  RELEASE_KEYSTORE_BASE64  -> содержимое release.keystore.base64.txt
  RELEASE_STORE_PASSWORD   -> введённый пароль хранилища
  RELEASE_KEY_ALIAS        -> $ALIAS
  RELEASE_KEY_PASSWORD     -> введённый пароль ключа

TEXT

echo "Отпечаток сертификата (пригодится для VK ID):"
keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" -storepass "$STORE_PASS" | grep -i "SHA256"

cat <<'TEXT'

ВАЖНО:
 - Сохраните release.keystore и пароли в надёжном месте.
 - Потеря ключа = невозможность обновить приложение под тем же package name.
 - НЕ коммитьте release.keystore (он уже в .gitignore).
 - После добавления секретов удалите release.keystore.base64.txt.
TEXT
