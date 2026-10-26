#!/usr/bin/env bash
# Generate a self-signed certificate for local/staging verification of the TLS
# edge. Production should mount an operator-issued certificate instead.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERT_DIR="${CERT_DIR:-$ROOT/certs}"
HOST="${CHATBI_PUBLIC_HOST:-${1:-localhost}}"
DAYS="${CERT_DAYS:-825}"

mkdir -p "$CERT_DIR"
chmod 700 "$CERT_DIR"

# Git Bash mangles /CN=... into a Windows path. Disable conversion and hand
# openssl Windows-style file paths so both the subject and outputs work.
OPENSSL_DIR="$CERT_DIR"
OPENSSL=(openssl)
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        OPENSSL_DIR="$(cygpath -m "$CERT_DIR")"
        OPENSSL=(env MSYS_NO_PATHCONV=1 openssl)
        ;;
esac

"${OPENSSL[@]}" req -x509 -nodes -newkey rsa:2048 -sha256 -days "$DAYS" \
    -keyout "$OPENSSL_DIR/server.key" \
    -out "$OPENSSL_DIR/server.crt" \
    -subj "/CN=$HOST" \
    -addext "subjectAltName=DNS:$HOST,DNS:localhost,IP:127.0.0.1"

chmod 600 "$CERT_DIR/server.key"
chmod 644 "$CERT_DIR/server.crt"

echo "Wrote $CERT_DIR/server.crt and $CERT_DIR/server.key for CN=$HOST (valid $DAYS days)"
openssl x509 -in "$CERT_DIR/server.crt" -noout -subject -dates
