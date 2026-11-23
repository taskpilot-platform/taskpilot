#!/bin/sh
set -eu

APP_PORT="${PORT:-${SERVER_PORT:-7860}}"
PROM_BIN="/usr/local/bin/prometheus"
TEMPLATE_FILE="/home/user/app/observability/prometheus-agent.yml.tmpl"
PROM_DIR="/tmp/prom-agent"

trim_ws() {
    printf '%s' "${1:-}" | awk '{gsub(/^[[:space:]]+|[[:space:]]+$/, ""); print}'
}

TRIMMED_REMOTE_WRITE_URL="$(trim_ws "${PROM_REMOTE_WRITE_URL:-}")"
TRIMMED_PROM_USERNAME="$(trim_ws "${PROM_USERNAME:-}")"
TRIMMED_API_TOKEN_CHECK="$(trim_ws "${PROM_API_TOKEN:-}")"
TRIMMED_SCRAPE_PASS_CHECK="$(trim_ws "${PROMETHEUS_SCRAPE_PASSWORD:-}")"

MISSING=""
[ -n "$TRIMMED_REMOTE_WRITE_URL" ] || MISSING="$MISSING PROM_REMOTE_WRITE_URL"
[ -n "$TRIMMED_PROM_USERNAME" ] || MISSING="$MISSING PROM_USERNAME"
[ -n "$TRIMMED_API_TOKEN_CHECK" ] || MISSING="$MISSING PROM_API_TOKEN"
[ -n "$TRIMMED_SCRAPE_PASS_CHECK" ] || MISSING="$MISSING PROMETHEUS_SCRAPE_PASSWORD"

setup_prometheus_agent() {
    (
        umask 077 &&
        mkdir -p "$PROM_DIR/data" &&
        chmod 700 "$PROM_DIR" "$PROM_DIR/data" &&
        printf '%s' "$PROMETHEUS_SCRAPE_PASSWORD" > "$PROM_DIR/scrape_password" &&
        printf '%s' "$PROM_API_TOKEN" > "$PROM_DIR/remote_write_token" &&
        chmod 600 "$PROM_DIR/scrape_password" "$PROM_DIR/remote_write_token" &&
        TRIMMED_SCRAPE_USER="$(trim_ws "${PROMETHEUS_SCRAPE_USERNAME:-}")" &&
        if [ -z "$TRIMMED_SCRAPE_USER" ]; then TRIMMED_SCRAPE_USER="prometheus"; fi &&
        TRIMMED_APP_ENV="$(trim_ws "${APP_ENV:-}")" &&
        if [ -z "$TRIMMED_APP_ENV" ]; then TRIMMED_APP_ENV="prod"; fi &&
        APP_ENV_VAL="$TRIMMED_APP_ENV" \
        SCRAPE_USER_VAL="$TRIMMED_SCRAPE_USER" \
        APP_PORT_VAL="$APP_PORT" \
        REMOTE_WRITE_URL_VAL="$TRIMMED_REMOTE_WRITE_URL" \
        PROM_USER_VAL="$TRIMMED_PROM_USERNAME" \
        awk '
            function replace_all(str, pat, rep,    idx, out) {
                out = ""
                while ((idx = index(str, pat)) > 0) {
                    out = out substr(str, 1, idx - 1) rep
                    str = substr(str, idx + length(pat))
                }
                return out str
            }
            function yaml_sq(val) {
                return replace_all(val, "\x27", "\x27\x27")
            }
            {
                line = $0
                line = replace_all(line, "__APP_ENV__", yaml_sq(ENVIRON["APP_ENV_VAL"]))
                line = replace_all(line, "__SCRAPE_USERNAME__", yaml_sq(ENVIRON["SCRAPE_USER_VAL"]))
                line = replace_all(line, "__APP_PORT__", yaml_sq(ENVIRON["APP_PORT_VAL"]))
                line = replace_all(line, "__REMOTE_WRITE_URL__", yaml_sq(ENVIRON["REMOTE_WRITE_URL_VAL"]))
                line = replace_all(line, "__PROM_USERNAME__", yaml_sq(ENVIRON["PROM_USER_VAL"]))
                print line
            }
        ' "$TEMPLATE_FILE" > "$PROM_DIR/prometheus.yml.tmp" &&
        [ -s "$PROM_DIR/prometheus.yml.tmp" ] &&
        chmod 600 "$PROM_DIR/prometheus.yml.tmp" &&
        mv "$PROM_DIR/prometheus.yml.tmp" "$PROM_DIR/prometheus.yml"
    )
}

if [ -n "$MISSING" ]; then
    echo "[monitoring] prometheus disabled: missing env${MISSING}"
elif [ ! -x "$PROM_BIN" ]; then
    echo "[monitoring] warning: prometheus binary missing at $PROM_BIN; starting app without prometheus agent" >&2
elif setup_prometheus_agent; then
    "$PROM_BIN" \
        --agent \
        --config.file="$PROM_DIR/prometheus.yml" \
        --storage.agent.path="$PROM_DIR/data" \
        --web.listen-address=127.0.0.1:9090 &
    echo "[monitoring] prometheus agent started"
else
    rm -f "$PROM_DIR/prometheus.yml.tmp" 2>/dev/null || true
    echo "[monitoring] warning: setup failed; starting app without prometheus agent" >&2
fi

# ------------------------------------------------------------------------------
# OpenTelemetry OTLP (Traces -> Grafana Cloud Tempo, Logs -> Grafana Cloud Loki)
# Prometheus Agent remains the sole metrics transport (never duplicate via OTLP).
# Supports EITHER:
#   A) Standard Grafana Cloud copy-paste env vars:
#        OTEL_EXPORTER_OTLP_ENDPOINT + OTEL_EXPORTER_OTLP_HEADERS
#   B) Convenience credentials (auto-encoded to Basic Auth header):
#        OTEL_EXPORTER_OTLP_ENDPOINT + OTEL_INSTANCE_ID + OTEL_API_TOKEN
# ------------------------------------------------------------------------------
export OTEL_METRICS_EXPORTER="${OTEL_METRICS_EXPORTER:-none}"
export OTEL_EXPORTER_OTLP_PROTOCOL="${OTEL_EXPORTER_OTLP_PROTOCOL:-http/protobuf}"

TRIMMED_OTEL_ENDPOINT="$(trim_ws "${OTEL_EXPORTER_OTLP_ENDPOINT:-}")"
TRIMMED_OTEL_HEADERS="$(trim_ws "${OTEL_EXPORTER_OTLP_HEADERS:-}")"
TRIMMED_OTEL_INSTANCE_ID="$(trim_ws "${OTEL_INSTANCE_ID:-}")"
TRIMMED_OTEL_API_TOKEN_CHECK="$(trim_ws "${OTEL_API_TOKEN:-}")"

if [ -n "$TRIMMED_OTEL_ENDPOINT" ]; then
    export OTEL_EXPORTER_OTLP_ENDPOINT="$TRIMMED_OTEL_ENDPOINT"
    if [ -n "$TRIMMED_OTEL_HEADERS" ]; then
        # Spring Boot 4's W3CHeaderParser invokes URLDecoder.decode whenever '%' is present,
        # which would turn raw '+' characters inside Base64 Basic tokens into spaces.
        case "$TRIMMED_OTEL_HEADERS" in
            *%*)
                SAFE_OTEL_HEADERS="$(printf '%s' "$TRIMMED_OTEL_HEADERS" | awk '{gsub(/\+/, "%2B"); print}')"
                export OTEL_EXPORTER_OTLP_HEADERS="$SAFE_OTEL_HEADERS"
                ;;
            *)
                export OTEL_EXPORTER_OTLP_HEADERS="$TRIMMED_OTEL_HEADERS"
                ;;
        esac
        echo "[monitoring] OTLP traces+logs enabled (endpoint=$TRIMMED_OTEL_ENDPOINT)"
    elif [ -n "$TRIMMED_OTEL_INSTANCE_ID" ] && [ -n "$TRIMMED_OTEL_API_TOKEN_CHECK" ]; then
        OTEL_BASIC_B64="$(printf '%s:%s' "$TRIMMED_OTEL_INSTANCE_ID" "$TRIMMED_OTEL_API_TOKEN_CHECK" | base64 | tr -d '\r\n')"
        export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic ${OTEL_BASIC_B64}"
        echo "[monitoring] OTLP traces+logs enabled via instance credentials (endpoint=$TRIMMED_OTEL_ENDPOINT)"
    else
        unset OTEL_EXPORTER_OTLP_ENDPOINT
        echo "[monitoring] OTLP traces+logs disabled: missing OTEL_EXPORTER_OTLP_HEADERS (or OTEL_INSTANCE_ID + OTEL_API_TOKEN)"
    fi
else
    echo "[monitoring] OTLP traces+logs disabled: missing env OTEL_EXPORTER_OTLP_ENDPOINT"
fi

exec java \
    --enable-preview \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.net.preferIPv4Stack=true \
    -Djava.security.egd=file:/dev/./urandom \
    -jar app.jar "$@"
