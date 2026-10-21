#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077

REPO="${MOBILEHORIZON_REPO:-Ph04/MobileHorizonNative}"
BRANCH="feature/android-native-import"
KEY_DIR="$HOME/.mobilehorizon-signing"
KEYSTORE="$KEY_DIR/mobilehorizon-signing.jks"
PASSWORD_FILE="$KEY_DIR/store-password"
BASE64_FILE="$KEY_DIR/keystore.base64"

die() { printf 'ERRO: %s\n' "$*" >&2; exit 1; }
info() { printf '\n==> %s\n' "$*"; }

command -v keytool >/dev/null 2>&1 || die "Instale o JDK no Termux: pkg install openjdk-17"
command -v gh >/dev/null 2>&1 || die "Instale o GitHub CLI: pkg install gh"
command -v base64 >/dev/null 2>&1 || die "O comando base64 não foi encontrado."
command -v od >/dev/null 2>&1 || die "O comando od não foi encontrado; instale coreutils."
gh auth status >/dev/null 2>&1 || die "Autentique sua conta primeiro com: gh auth login"
gh repo view "$REPO" --json nameWithOwner >/dev/null 2>&1 || die "Sua conta gh não tem acesso ao repositório $REPO."
SECRET_TABLE="$(gh secret list --repo "$REPO" 2>/dev/null)" || die "Não consegui consultar os nomes dos secrets. Confira as permissões da conta gh."
SECRET_NAMES="$(printf '%s\\n' "$SECRET_TABLE" | awk 'NR > 1 {print $1}')"
EXISTING_SIGNING_SECRETS=0
for SECRET_NAME in MOBILEHORIZON_KEYSTORE_BASE64 MOBILEHORIZON_KEYSTORE_PASSWORD MOBILEHORIZON_KEY_ALIAS MOBILEHORIZON_KEY_PASSWORD; do
  if printf '%s\\n' "$SECRET_NAMES" | grep -Fxq "$SECRET_NAME"; then
    EXISTING_SIGNING_SECRETS=$((EXISTING_SIGNING_SECRETS + 1))
  fi
done

mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"

if [[ -s "$KEYSTORE" && -s "$PASSWORD_FILE" ]]; then
  info "Reutilizando a chave persistente já existente; nenhuma chave nova será criada."
  STORE_PASS="$(cat "$PASSWORD_FILE")"
elif [[ ! -e "$KEYSTORE" && ! -e "$PASSWORD_FILE" ]]; then
  if [[ "$EXISTING_SIGNING_SECRETS" -gt 0 ]]; then
    die "O GitHub já tem pelo menos um secret de assinatura, mas a keystore local não existe. Não vou sobrescrever a chave de atualização. Recupere o arquivo original a partir do seu backup."
  fi
  info "Gerando uma chave de assinatura nova no armazenamento privado do Termux."
  STORE_PASS="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
  TMP_KEYSTORE="$KEY_DIR/mobilehorizon-signing.tmp.jks"
  TMP_PASSWORD="$KEY_DIR/store-password.tmp"
  trap 'rm -f "$TMP_KEYSTORE" "$TMP_PASSWORD" "$BASE64_FILE"' EXIT
  keytool -genkeypair \
    -alias mobilehorizon \
    -keyalg RSA -keysize 3072 -validity 10000 \
    -storetype JKS \
    -keystore "$TMP_KEYSTORE" \
    -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
    -dname "CN=MobileHorizonNative, O=Ph04, C=BR" >/dev/null 2>&1
  printf '%s\n' "$STORE_PASS" > "$TMP_PASSWORD"
  chmod 600 "$TMP_PASSWORD" "$TMP_KEYSTORE"
  mv "$TMP_PASSWORD" "$PASSWORD_FILE"
  mv "$TMP_KEYSTORE" "$KEYSTORE"
  trap - EXIT
else
  die "Encontrei apenas parte da chave local. Por segurança, não vou gerar outra nem substituir arquivos; confira $KEY_DIR."
fi

keytool -list -keystore "$KEYSTORE" -storetype JKS -storepass "$STORE_PASS" -alias mobilehorizon >/dev/null 2>&1 \
  || die "Não consegui validar a keystore existente. Não alterei os segredos do GitHub."

info "Enviando a keystore e as credenciais como GitHub Actions secrets (sem colocá-las no código ou no log)."
base64 "$KEYSTORE" | tr -d '\r\n' > "$BASE64_FILE"
chmod 600 "$BASE64_FILE"
gh secret set MOBILEHORIZON_KEYSTORE_BASE64 --repo "$REPO" < "$BASE64_FILE"
printf '%s' "$STORE_PASS" | gh secret set MOBILEHORIZON_KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "mobilehorizon" | gh secret set MOBILEHORIZON_KEY_ALIAS --repo "$REPO"
printf '%s' "$STORE_PASS" | gh secret set MOBILEHORIZON_KEY_PASSWORD --repo "$REPO"
rm -f "$BASE64_FILE"

info "Secrets cadastrados. Iniciando uma nova compilação usando a chave persistente."
gh workflow run "Android build, official downloader and Horizon runtime" --repo "$REPO" --ref "$BRANCH"

printf '\nConcluído. A keystore foi preservada em:\n%s\n' "$KEYSTORE"
printf 'Guarde uma cópia segura da pasta %s; não a envie para o repositório nem para chats.\n' "$KEY_DIR"
printf 'A compilação pode ser acompanhada em: https://github.com/%s/actions\n' "$REPO"
