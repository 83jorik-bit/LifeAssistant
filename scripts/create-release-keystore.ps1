# Создание релизного ключа подписи и подготовка значений для секретов GitHub.
#
# Запускать на своём компьютере (PowerShell), НЕ в CI:
#   powershell -ExecutionPolicy Bypass -File scripts\create-release-keystore.ps1
#
# Ключ остаётся только у вас. Это важно: тот, у кого есть этот файл и пароли,
# может выпустить обновление вашего приложения от вашего имени.

$ErrorActionPreference = "Stop"

$KeystorePath = "release.keystore"
$Alias = "lifenavigator"

if (Test-Path $KeystorePath) {
    Write-Host "Файл $KeystorePath уже существует." -ForegroundColor Yellow
    Write-Host "Если создать новый ключ поверх старого, обновления приложения перестанут ставиться." -ForegroundColor Yellow
    Write-Host "Удалите или переименуйте старый файл вручную, если уверены." -ForegroundColor Yellow
    exit 1
}

# keytool входит в состав JDK. Если команда не найдётся — установите JDK 17
# или добавьте в PATH папку bin из Android Studio (jbr\bin).
$StorePassword = Read-Host "Придумайте пароль ХРАНИЛИЩА (минимум 6 символов)"
$KeyPassword = Read-Host "Придумайте пароль КЛЮЧА (можно тот же)"

Write-Host ""
Write-Host "Создаю ключ на 30 лет..." -ForegroundColor Cyan

keytool -genkeypair -v `
    -keystore $KeystorePath `
    -alias $Alias `
    -keyalg RSA -keysize 2048 -validity 10950 `
    -storepass $StorePassword `
    -keypass $KeyPassword `
    -dname "CN=LifeNavigator, OU=Dev, O=LifeNavigator, L=NA, S=NA, C=RU"

if ($LASTEXITCODE -ne 0) {
    Write-Host "keytool завершился с ошибкой. Убедитесь, что установлен JDK." -ForegroundColor Red
    exit 1
}

# Base64 в одну строку — именно в таком виде ключ кладётся в секрет GitHub.
$Base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($KeystorePath))
Set-Content -Path "release.keystore.base64.txt" -Value $Base64 -NoNewline

Write-Host ""
Write-Host "Готово. Теперь создайте четыре секрета в репозитории:" -ForegroundColor Green
Write-Host "  Settings -> Secrets and variables -> Actions -> New repository secret"
Write-Host ""
Write-Host "  RELEASE_KEYSTORE_BASE64  -> содержимое файла release.keystore.base64.txt"
Write-Host "  RELEASE_STORE_PASSWORD   -> пароль хранилища, который вы ввели"
Write-Host "  RELEASE_KEY_ALIAS        -> $Alias"
Write-Host "  RELEASE_KEY_PASSWORD     -> пароль ключа, который вы ввели"
Write-Host ""
Write-Host "Отпечаток сертификата (пригодится для настройки VK ID):" -ForegroundColor Cyan
keytool -list -v -keystore $KeystorePath -alias $Alias -storepass $StorePassword | Select-String "SHA256"

Write-Host ""
Write-Host "ВАЖНО:" -ForegroundColor Yellow
Write-Host " - Сохраните release.keystore и оба пароля в надёжном месте (менеджер паролей)."
Write-Host " - Потеря ключа = невозможность обновить приложение под тем же package name."
Write-Host " - НЕ коммитьте release.keystore в репозиторий (он уже в .gitignore)."
Write-Host " - После добавления секретов удалите release.keystore.base64.txt."
