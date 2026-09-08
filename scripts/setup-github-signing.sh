#!/usr/bin/env bash
set -euo pipefail

repo="${1:-aar0u/manga-extensions}"
alias="copy3000"
output_dir=".github-secrets"
key_store="$output_dir/signingkey.jks"
secrets_file="$output_dir/github-actions-variables.txt"

for command in keytool openssl base64; do
    command -v "$command" >/dev/null || {
        echo "Missing required command: $command" >&2
        exit 1
    }
done

if [[ -e "$key_store" || -e "$secrets_file" ]]; then
    echo "$output_dir already contains signing material; refusing to overwrite it." >&2
    exit 1
fi

read -r -s -p "Signing password (at least 6 characters): " signing_password
echo
if ((${#signing_password} < 6)); then
    echo "Signing password must be at least 6 characters." >&2
    exit 1
fi

umask 077
mkdir -p "$output_dir"
export KEY_STORE_PASSWORD="$signing_password"

keytool -genkeypair \
    -alias "$alias" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -dname "CN=$repo" \
    -keystore "$key_store" \
    -storetype JKS \
    -storepass:env KEY_STORE_PASSWORD \
    -keypass:env KEY_STORE_PASSWORD

{
    printf 'SIGNING_KEY='
    base64 < "$key_store" | tr -d '\r\n'
    printf '\nALIAS=%s\n' "$alias"
    printf 'KEY_STORE_PASSWORD=%s\n' "$signing_password"
    printf 'KEY_PASSWORD=%s\n' "$signing_password"
} > "$secrets_file"
unset KEY_STORE_PASSWORD signing_password

echo "Created $secrets_file and $key_store. Keep $output_dir private and out of Git."
echo "In GitHub: Settings -> Secrets and variables -> Actions -> Variables -> New repository variable."
echo "Create SIGNING_KEY, ALIAS, KEY_STORE_PASSWORD, and KEY_PASSWORD from $secrets_file."
