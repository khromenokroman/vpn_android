#!/usr/bin/env bash
# Автотест подключения Android-клиента PomaVPN к серверу poma-vpn 0.5.0.0 на эмуляторе.
# Сервер поднимается в docker из официального пакета релиза v0.5.0.0,
# эмулятор - headless AVD Pixel_6_Pro_13, приложение ставится debug-APK
# и подключается к серверу через 10.0.2.2 (адрес хоста для эмулятора).
#
# Проверки: туннель устанавливается (logcat «Туннель установлен»),
# ping адреса сервера в туннеле (192.168.200.1) проходит, с неверным
# ключом туннель не устанавливается и по таймауту подключения публикуется
# ERROR с закрытием VPN-интерфейса, после docker restart сервера
# туннель восстанавливается не позже чем через 20 с, отключение и
# повторное подключение через UI в ТОМ ЖЕ процессе работает (pid не меняется),
# после docker pause/unpause сервера NO_RESPONSE сменяется EV_TUNNEL_RESTORED
# и ping снова проходит.
# Скриншоты пишутся в tasks/button/ основного репозитория (~/AndroidStudioProjects/vpn/tasks/button/).
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TASKS_DIR="$HOME/AndroidStudioProjects/vpn/tasks/button"
SDK_DIR="$HOME/Android/Sdk"
ADB="$SDK_DIR/platform-tools/adb"
EMULATOR="$SDK_DIR/emulator/emulator"
AVD_NAME="Pixel_6_Pro_13"
CONTAINER="pomavpn-e2e-server"
IMAGE="pomavpn-e2e-server:latest"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="io.github.khromenokroman.pomavpn"

DEB_URL="https://github.com/khromenokroman/vpn/releases/download/v0.5.0.0/poma-vpn-server_0.5.0.0_amd64-debian13.deb"
WORK_DIR="$(mktemp -d /tmp/pomavpn-e2e.XXXXXX)"
KEY_FILE="$WORK_DIR/key"

PASS=0
FAIL=0
EMU_PID=""

log() { echo "[e2e] $*"; }

ok() { log "PASS: $*"; PASS=$((PASS + 1)); }
bad() { log "FAIL: $*"; FAIL=$((FAIL + 1)); }

cleanup() {
    log "очистка"
    [ -n "$EMU_PID" ] && kill "$EMU_PID" 2>/dev/null
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

fail_env() {
    log "Невозможно продолжить: $*"
    exit 1
}

wait_logcat_marker() {
    # $1 - маркер, $2 - таймаут с
    local deadline=$((SECONDS + $2))
    while [ $SECONDS -lt $deadline ]; do
        if "$ADB" logcat -d 2>/dev/null | grep -qF "$1"; then
            return 0
        fi
        sleep 3
    done
    return 1
}

wait_ui_text() {
    # $1 - текст на экране, $2 - таймаут с
    local deadline=$((SECONDS + $2))
    "$ADB" shell rm -f /sdcard/ui-wait.xml >/dev/null 2>&1
    while [ $SECONDS -lt $deadline ]; do
        "$ADB" shell timeout 15 uiautomator dump /sdcard/ui-wait.xml >/dev/null 2>&1
        if "$ADB" shell cat /sdcard/ui-wait.xml 2>/dev/null | grep -qF "$1"; then
            return 0
        fi
        # В некоторых образах AVD UiAutomation зависает при активной анимации.
        if [ "$1" = "Отключено" ] && \
            "$ADB" logcat -d 2>/dev/null | grep -qF "applyDebugExtras: action=null autoconnect=false state=disconnected"; then
            return 0
        fi
        sleep 2
    done
    return 1
}

wait_ui_text_dump() {
    # ждёт появления текста именно в иерархии UI (без резервного logcat-маркера),
    # чтобы скриншот не попал на splash screen; $1 - текст, $2 - таймаут с
    local deadline=$((SECONDS + $2))
    while [ $SECONDS -lt $deadline ]; do
        "$ADB" shell timeout 15 uiautomator dump /sdcard/ui-wait.xml >/dev/null 2>&1
        if "$ADB" shell cat /sdcard/ui-wait.xml 2>/dev/null | grep -qF "$1"; then
            return 0
        fi
        sleep 1
    done
    return 1
}

wait_tun() {
    # $1 - present|absent: ждёт появления/исчезновения VPN-интерфейса в маршрутах, $2 - таймаут с
    local deadline=$((SECONDS + $2))
    while [ $SECONDS -lt $deadline ]; do
        if "$ADB" shell ip route 2>/dev/null | grep -q "dev tun"; then
            [ "$1" = present ] && return 0
        else
            [ "$1" = absent ] && return 0
        fi
        sleep 2
    done
    return 1
}

ping_tunnel() {
    "$ADB" shell ping -c3 -W2 192.168.200.1 2>&1 | grep -q "bytes from"
}

stop_vpn() {
    # startservice недоступен для неэкспортированного сервиса: останавливаем приложение целиком
    "$ADB" shell am force-stop "$PACKAGE"
    sleep 3
}

launch_client() {
    # $1 - ключ, $2 - autoconnect, $3 - адрес сервера (по умолчанию 10.0.2.2)
    "$ADB" shell am start -n "$PACKAGE/com.example.vpn.MainActivity" \
        --es server "${3:-10.0.2.2}" --ei port 51820 --es tunIp 192.168.200.2 \
        --es key "$1" --ei mtu 1380 --ez autoconnect "$2" >/dev/null 2>&1
}

debug_action() {
    # $1 - connect|disconnect: отладочный extra в УЖЕ запущенную активность
    # (singleTop + FLAG_ACTIVITY_SINGLE_TOP: доставляется в onNewIntent без нового процесса)
    "$ADB" shell am start -n "$PACKAGE/com.example.vpn.MainActivity" -f 0x20000000 --es action "$1" >/dev/null 2>&1
}

# --- предусловия -------------------------------------------------------------
command -v docker >/dev/null || fail_env "docker не найден"
command -v curl >/dev/null || fail_env "curl не найден"
[ -x "$EMULATOR" ] || fail_env "эмулятор не найден: $EMULATOR"
[ -x "$ADB" ] || fail_env "adb не найден: $ADB"
docker info >/dev/null 2>&1 || fail_env "docker-демон не запущен"
[ -w /dev/kvm ] || log "внимание: /dev/kvm недоступен, эмулятор будет без ускорения"

mkdir -p "$TASKS_DIR"

if [ ! -f "$APK" ]; then
    log "сборка debug-APK"
    (cd "$PROJECT_DIR" && ./gradlew --offline assembleDebug) || fail_env "сборка APK не удалась"
fi

# --- сервер в docker ---------------------------------------------------------
log "скачивание пакета сервера"
curl -fsSL "$DEB_URL" -o "$WORK_DIR/poma-vpn-server.deb" || fail_env "не удалось скачать $DEB_URL"

cat > "$WORK_DIR/Dockerfile" <<'EOF'
FROM debian:trixie
COPY poma-vpn-server.deb /tmp/poma-vpn-server.deb
RUN apt-get update && apt-get install -y --no-install-recommends /tmp/poma-vpn-server.deb \
    && rm -f /tmp/poma-vpn-server.deb && apt-get clean
EOF

log "сборка docker-образа сервера"
docker build -q -t "$IMAGE" -f "$WORK_DIR/Dockerfile" "$WORK_DIR" >/dev/null \
    || fail_env "сборка образа не удалась"

KEY="$(head -c 32 /dev/urandom | base64)"
printf '%s\n' "$KEY" > "$KEY_FILE"
chmod 600 "$KEY_FILE"

log "запуск контейнера сервера"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
# ключ копируется внутрь контейнера: сервер требует владельца root и права 600
docker run -d --name "$CONTAINER" \
    --cap-add NET_ADMIN --device /dev/net/tun \
    -p 51820:51820/udp \
    -v "$KEY_FILE:/tmp/poma-key-ro:ro" \
    "$IMAGE" \
    sh -c 'install -m 600 -o root -g root /tmp/poma-key-ro /etc/poma-vpn/key && exec vpn-server -p 51820 -e eth0 -k /etc/poma-vpn/key' \
    >/dev/null || fail_env "запуск контейнера не удался"
sleep 2
docker ps --filter "name=$CONTAINER" --format '{{.Status}}' | grep -q Up \
    || { docker logs "$CONTAINER" 2>&1 | tail -10; fail_env "контейнер сервера упал"; }

# --- эмулятор ----------------------------------------------------------------
log "запуск эмулятора $AVD_NAME (headless)"
"$ADB" kill-server >/dev/null 2>&1 || true
"$EMULATOR" -avd "$AVD_NAME" -no-window -no-audio -no-snapshot-save \
    > "$WORK_DIR/emulator.log" 2>&1 &
EMU_PID=$!

log "ожидание загрузки системы"
"$ADB" wait-for-device
boot_ok=""
for _ in $(seq 1 80); do
    if [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        boot_ok=1
        break
    fi
    sleep 3
done
[ -n "$boot_ok" ] || { fail_env "эмулятор не загрузился за 240 с"; }
log "система загружена"

"$ADB" logcat -c >/dev/null 2>&1 || true

log "установка APK"
"$ADB" install -r "$APK" >/dev/null || fail_env "adb install не удался"
# сброс данных: иначе состояние/настройки из прошлой сессии на снапшоте AVD
# помешают автоподключению
"$ADB" shell pm clear "$PACKAGE" >/dev/null || true
"$ADB" shell appops set "$PACKAGE" ACTIVATE_VPN allow || fail_env "appops не удался"
# без гранта headless-эмулятор оставит висеть диалог запроса разрешения на уведомления
"$ADB" shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS || true

# --- экран DISCONNECTED ------------------------------------------------------
# каждая фаза - force-stop + свежий запуск: повторный am start по уже
# запущенной активити (standard launchMode) не даёт ни onCreate, ни onNewIntent
log "запуск без автоподключения (экран ожидания)"
launch_client "$KEY" false
if wait_ui_text "Отключено" 60; then
    ok "основной экран загружен («Отключено» виден)"
else
    bad "основной экран не загрузился за 60 с"
fi
wait_ui_text_dump "Отключено" 30 || log "предупреждение: UI не подтверждён dump-ом, скриншот может попасть на splash"
"$ADB" exec-out screencap -p > "$TASKS_DIR/disconnected.png" && \
    ok "скриншот disconnected.png" || bad "скриншот disconnected.png"

# --- подключение -------------------------------------------------------------
log "подключение к недостижимому серверу (экран CONNECTING)"
stop_vpn
# 203.0.113.10 (TEST-NET-3) не отвечает: подключение висит до таймаута 45 с,
# состояние CONNECTING гарантированно видно на экране
launch_client "$KEY" true 203.0.113.10
wait_ui_text_dump "Подключение..." 30 || log "предупреждение: «Подключение...» не найдено в UI"
"$ADB" exec-out screencap -p > "$TASKS_DIR/connecting.png" && \
    ok "скриншот connecting.png" || bad "скриншот connecting.png"
stop_vpn

log "автоподключение к серверу"
stop_vpn
launch_client "$KEY" true

if wait_logcat_marker "Туннель установлен" 60; then
    ok "туннель установлен (logcat «Туннель установлен»)"
else
    bad "туннель не установлен за 60 с"
    "$ADB" logcat -d | grep -E "MyVpnService|NativeVPN|AndroidRuntime|FATAL" | tail -30
fi

if ping_tunnel; then
    ok "ping 192.168.200.1 через туннель проходит"
else
    bad "ping 192.168.200.1 не проходит"
fi

"$ADB" exec-out screencap -p > "$TASKS_DIR/connected.png" && \
    ok "скриншот connected.png" || bad "скриншот connected.png"

# --- экран настроек ----------------------------------------------------------
UI_XML="$WORK_DIR/ui.xml"
"$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
"$ADB" pull /sdcard/ui.xml "$UI_XML" >/dev/null 2>&1
GEAR_BOUNDS=$(grep -o 'bounds="[^"]*"[^>]*content-desc="Настройки"' "$UI_XML" 2>/dev/null | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
if [ -z "$GEAR_BOUNDS" ]; then
    GEARS=$(grep -o 'content-desc="Настройки"[^>]*bounds="[^"]*"' "$UI_XML" 2>/dev/null | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
    GEAR_BOUNDS="$GEARS"
fi
if [ -n "$GEAR_BOUNDS" ]; then
    X1=$(echo "$GEAR_BOUNDS" | sed 's/^\[\([0-9]*\),.*/\1/')
    Y1=$(echo "$GEAR_BOUNDS" | sed 's/^\[[0-9]*,\([0-9]*\)\].*/\1/')
    X2=$(echo "$GEAR_BOUNDS" | sed 's/.*\]\[\([0-9]*\),.*/\1/')
    Y2=$(echo "$GEAR_BOUNDS" | sed 's/.*,\([0-9]*\)\]$/\1/')
    "$ADB" shell input tap $(( (X1 + X2) / 2 )) $(( (Y1 + Y2) / 2 )) >/dev/null
    sleep 2
    "$ADB" exec-out screencap -p > "$TASKS_DIR/settings.png" && \
        ok "скриншот settings.png" || bad "скриншот settings.png"
else
    bad "кнопка настроек не найдена в иерархии UI, settings.png не снят"
fi

# --- неверный ключ -----------------------------------------------------------
log "проверка с неверным ключом"
stop_vpn
"$ADB" logcat -c >/dev/null 2>&1 || true
launch_client "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" true
if wait_logcat_marker "Туннель установлен" 25; then
    bad "туннель установился с неверным ключом"
else
    ok "с неверным ключом туннель не устанавливается"
fi
# подключение по таймауту должно завершиться ERROR, а не вечным CONNECTING,
# и VPN-интерфейс должен быть закрыт (системный интернет возвращается)
if wait_logcat_marker "Сервер не ответил на подключение" 55; then
    ok "неудачное подключение опубликовало ERROR (таймаут)"
else
    bad "ERROR о таймауте подключения не опубликован"
fi
if wait_tun absent 10; then
    ok "после ошибки старта VPN-интерфейс не остался поднятым"
else
    bad "после ошибки старта VPN-интерфейс остался поднятым"
fi
"$ADB" exec-out screencap -p > "$TASKS_DIR/error.png" && \
    ok "скриншот error.png" || bad "скриншот error.png"

# --- возврат к рабочему ключу и перезапуск сервера ---------------------------
log "повторное подключение с верным ключом"
stop_vpn
launch_client "$KEY" true
if wait_logcat_marker "Туннель установлен" 60 && ping_tunnel; then
    ok "повторное подключение работает"
else
    bad "повторное подключение не работает"
fi

log "docker restart сервера"
docker restart "$CONTAINER" >/dev/null
reconnected=""
for _ in $(seq 1 10); do
    if ping_tunnel; then
        reconnected=1
        break
    fi
    sleep 2
done
if [ -n "$reconnected" ]; then
    ok "после перезапуска сервера туннель восстановлен не позже 20 с"
else
    bad "после перезапуска сервера туннель не восстановился за 20 с"
fi

# --- повторное подключение в ТОМ ЖЕ процессе ----------------------------------
# без force-stop: активность уже запущена, команды идут через onNewIntent.
# Сервер жив и помнит сессию: отвечает ACCEPT в известной сессии - до фикса
# EV_TUNNEL_UP не приходил и экран навсегда зависал на «Подключение...»
log "отключение и повторное подключение в том же процессе"
APP_PID_BEFORE="$("$ADB" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')"
"$ADB" logcat -c >/dev/null 2>&1 || true
debug_action disconnect
if wait_tun absent 20; then
    ok "после action=disconnect VPN-интерфейс закрыт"
else
    bad "после action=disconnect VPN-интерфейс не закрылся"
fi
debug_action connect
if wait_logcat_marker "Туннель установлен" 60 && ping_tunnel; then
    ok "повторное подключение в том же процессе: туннель установлен, ping проходит"
else
    bad "повторное подключение в том же процессе не работает"
fi
APP_PID_AFTER="$("$ADB" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')"
if [ -n "$APP_PID_BEFORE" ] && [ "$APP_PID_BEFORE" = "$APP_PID_AFTER" ]; then
    ok "pid процесса не изменился ($APP_PID_BEFORE)"
else
    bad "процесс перезапустился: pid '$APP_PID_BEFORE' -> '$APP_PID_AFTER'"
fi

# --- обрыв связи: docker pause/unpause ----------------------------------------
# Сервер заморожен: keep-alive не доходят, через 35 с NO_RESPONSE.
# После unpause сервер помнит сессию и отвечает ACCEPT (не NEW_SESSION):
# до фикса EV_TUNNEL_RESTORED не приходил и «Нет ответа сервера» не снималось
log "обрыв связи: docker pause сервера"
"$ADB" logcat -c >/dev/null 2>&1 || true
docker pause "$CONTAINER" >/dev/null
if wait_logcat_marker "onNativeEvent code=5" 70; then
    ok "NO_RESPONSE опубликован, пока сервер заморожен"
else
    bad "NO_RESPONSE не опубликован за 70 с"
fi
"$ADB" exec-out screencap -p > "$TASKS_DIR/no-response.png" && \
    ok "скриншот no-response.png" || bad "скриншот no-response.png"
docker unpause "$CONTAINER" >/dev/null
if wait_logcat_marker "onNativeEvent code=3" 25 && ping_tunnel; then
    ok "после возврата связи EV_TUNNEL_RESTORED, ping проходит"
else
    bad "после возврата связи туннель не восстановился за 25 с"
fi

# --- итог --------------------------------------------------------------------
log "итог: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
