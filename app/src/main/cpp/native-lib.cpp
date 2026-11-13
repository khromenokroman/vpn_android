#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <jni.h>
#include <netinet/in.h>
#include <poll.h>
#include <sodium.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <thread>

#include "crypto.hpp"
#include "packet.hpp"
#include "replay.hpp"

#define LOG_TAG "NativeVPN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/** @brief Период keep-alive: сервер забывает клиента через 60 с тишины. */
constexpr auto KEEP_ALIVE_INTERVAL = std::chrono::seconds(10);
/** @brief Столько нет принятого keep-alive - сообщаем в Kotlin, что сервер молчит. */
constexpr auto SERVER_SILENCE = std::chrono::seconds(35);
/** @brief Как часто потоки проверяют флаг остановки при тихом poll(). */
constexpr int POLL_TIMEOUT_MS = 200;
/** @brief Запас буфера шифротекста сверх BUFFER_SIZE. */
constexpr std::size_t BUFFER_SIZE = 65536;
constexpr std::size_t CRYPTO_SLACK = 128;
static_assert(CRYPTO_SLACK >= Crypto::overhead());

/** @brief События для onNativeEvent в Kotlin. */
enum EventCode : jint {
    EV_TUNNEL_UP = 1,      ///< первая NEW_SESSION: туннель установлен
    EV_TUNNEL_RESTARTED,   ///< повторная NEW_SESSION: сервер перезапущен
    EV_TUNNEL_RESTORED,    ///< keep-alive вернулся после NO_RESPONSE
    EV_CLOCK_SKEW,         ///< arg = расхождение часов, секунды со знаком
    EV_NO_RESPONSE,        ///< сервер молчит SERVER_SILENCE
    EV_OLD_PROTOCOL,       ///< сервер отвечает в формате до 0.5.0.0
    EV_FATAL,              ///< рабочий поток упал; причина через onNativeFatal(String)
};

std::atomic<bool> g_running{false};

std::thread g_tun_to_udp_thread;
std::thread g_udp_to_tun_thread;
std::thread g_control_thread;

int g_tun_fd = -1;
int g_udp_fd = -1;
int g_stop_pipe[2] = {-1, -1};

sockaddr_in g_server_addr{};
JavaVM* g_vm = nullptr;
jobject g_service_obj = nullptr;

std::unique_ptr<Crypto> g_crypto;
std::unique_ptr<TxSession> g_tx;
PeerState g_peer;
std::uint32_t g_tun_ip_be = 0;

std::atomic<unsigned long long> g_tx_packets{0};
std::atomic<unsigned long long> g_tx_bytes{0};
std::atomic<unsigned long long> g_rx_packets{0};
std::atomic<unsigned long long> g_rx_bytes{0};
std::atomic<unsigned long long> g_dropped{0};

/** @brief Когда последний раз видели сервер (steady_clock, нс). */
std::atomic<std::int64_t> g_server_seen_ns{0};
/** @brief Сообщено ли Kotlin, что сервер молчит. */
std::atomic<bool> g_no_response_reported{false};

// пишется только из udp_to_tun: rate-limit журнала
bool g_old_protocol_logged = false;
bool g_skew_logged = false;
bool g_stale_logged = false;

/** @brief Будит poll() в потоках, чтобы они заметили g_running == false. */
void requestStop() {
    if (g_stop_pipe[1] >= 0) {
        char c = 1;
        ssize_t ignored = write(g_stop_pipe[1], &c, 1);
        (void)ignored;
    }
}

/** @brief Отправляет onNativeFatal(причина) в сервис (определена ниже). */
void sendFatalEvent(const char* what);

/** @brief Останавливает потоки: флаг + будим pipe + сообщаем Kotlin причину. */
void failAndStop(const char* what) {
    LOGE("%s, errno=%d", what, errno);
    g_running = false;
    requestStop();
    sendFatalEvent(what);
}

std::int64_t steady_ns() {
    return std::chrono::steady_clock::now().time_since_epoch().count();
}

/** @brief JNIEnv текущего потока, при необходимости attach к JVM. */
JNIEnv* getJniEnv(bool& attached) {
    attached = false;
    if (!g_vm || !g_service_obj) {
        return nullptr;
    }

    JNIEnv* env = nullptr;
    jint rc = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("AttachCurrentThread не удался");
            return nullptr;
        }
        attached = true;
    } else if (rc != JNI_OK) {
        LOGE("GetEnv вернул ошибку %d", rc);
        return nullptr;
    }
    return env;
}

/** @brief Отправляет onNativeEvent(code, arg) в сервис. */
void sendEvent(jint code, jlong arg) {    bool attached = false;
    JNIEnv* env = getJniEnv(attached);
    if (!env) {
        return;
    }

    jclass cls = env->GetObjectClass(g_service_obj);
    jmethodID method = env->GetMethodID(cls, "onNativeEvent", "(IJ)V");
    if (method) {
        env->CallVoidMethod(g_service_obj, method, code, arg);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

/** @brief Отправляет onNativeFatal(причина) в сервис: рабочий поток не может продолжать. */
void sendFatalEvent(const char* what) {
    bool attached = false;
    JNIEnv* env = getJniEnv(attached);
    if (!env) {
        return;
    }

    jclass cls = env->GetObjectClass(g_service_obj);
    jmethodID method = env->GetMethodID(cls, "onNativeFatal", "(Ljava/lang/String;)V");
    if (method) {
        jstring reason = env->NewStringUTF(what);
        env->CallVoidMethod(g_service_obj, method, reason);
        env->DeleteLocalRef(reason);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

/** @brief Отправляет updateStats(5 счётчиков) в сервис раз в секунду. */
void sendStats() {
    bool attached = false;
    JNIEnv* env = getJniEnv(attached);
    if (!env) {
        return;
    }

    jclass cls = env->GetObjectClass(g_service_obj);
    jmethodID method = env->GetMethodID(cls, "updateStats", "(JJJJJ)V");
    if (method) {
        env->CallVoidMethod(g_service_obj, method,
                            static_cast<jlong>(g_tx_packets.load()),
                            static_cast<jlong>(g_tx_bytes.load()),
                            static_cast<jlong>(g_rx_packets.load()),
                            static_cast<jlong>(g_rx_bytes.load()),
                            static_cast<jlong>(g_dropped.load()));
    }
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

bool protectSocketFromJava(int fd) {
    bool attached = false;
    JNIEnv* env = getJniEnv(attached);
    if (!env) {
        return false;
    }

    jclass cls = env->GetObjectClass(g_service_obj);
    jmethodID method = env->GetMethodID(cls, "protectSocket", "(I)Z");
    if (!method) {
        LOGE("Не найден метод protectSocket(int)");
        if (attached) g_vm->DetachCurrentThread();
        return false;
    }

    jboolean result = env->CallBooleanMethod(g_service_obj, method, fd);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (attached) {
        g_vm->DetachCurrentThread();
    }
    return result == JNI_TRUE;
}

/** @brief Шлёт зашифрованный keep-alive с tx.next(), как и пакеты данных. */
void sendKeepAlive() {
    auto plain = make_keep_alive(g_tun_ip_be, wall_clock_ns());
    std::array<char, KEEP_ALIVE_SIZE + Crypto::overhead()> sealed{};
    std::size_t sealed_size = 0;
    if (!g_crypto || !g_crypto->seal(g_tx->next(), plain.data(), plain.size(), sealed.data(), sealed_size)) {
        return;
    }
    ssize_t sent = sendto(g_udp_fd, sealed.data(), sealed_size, 0,
                          reinterpret_cast<const sockaddr*>(&g_server_addr), sizeof(g_server_addr));
    if (sent < 0 && errno != EINTR && errno != EAGAIN && errno != ECONNREFUSED) {
        LOGW("Не удалось отправить keep-alive: errno=%d", errno);
    }
}

void tunToUdpLoop() {
    std::array<char, BUFFER_SIZE> plain_buffer;
    std::array<char, BUFFER_SIZE + CRYPTO_SLACK> encrypted_buffer;

    LOGI("tunToUdpLoop запущен");

    while (g_running) {
        pollfd pfd{g_tun_fd, POLLIN, 0};
        int prc = poll(&pfd, 1, POLL_TIMEOUT_MS);
        if (prc < 0) {
            if (errno == EINTR) {
                continue;
            }
            failAndStop("poll TUN ошибка");
            break;
        }
        if (prc == 0 || !(pfd.revents & POLLIN)) {
            continue;
        }

        ssize_t n = read(g_tun_fd, plain_buffer.data(), plain_buffer.size());
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) {
                continue;
            }
            failAndStop("Ошибка чтения из TUN");
            break;
        }
        if (n == 0) {
            continue;
        }

        // IPv6 и мусор в туннель не отправляются: сервер их всё равно отбросит
        if (!parse_ipv4_packet(plain_buffer.data(), static_cast<std::size_t>(n))) {
            continue;
        }

        std::size_t encrypted_size = 0;
        if (!g_crypto->seal(g_tx->next(), plain_buffer.data(), static_cast<std::size_t>(n),
                            encrypted_buffer.data(), encrypted_size)) {
            LOGE("Ошибка шифрования пакета");
            continue;
        }

        ssize_t sent = sendto(g_udp_fd, encrypted_buffer.data(), encrypted_size, 0,
                              reinterpret_cast<const sockaddr*>(&g_server_addr), sizeof(g_server_addr));
        if (sent >= 0) {
            g_tx_packets.fetch_add(1, std::memory_order_relaxed);
            g_tx_bytes.fetch_add(static_cast<unsigned long long>(sent), std::memory_order_relaxed);
        } else if (errno != EINTR && errno != EAGAIN && errno != ECONNREFUSED) {
            LOGW("Ошибка отправки на сервер: errno=%d", errno);
        }
    }

    LOGI("tunToUdpLoop остановлен");
}

void udpToTunLoop() {
    std::array<char, BUFFER_SIZE + CRYPTO_SLACK> encrypted_buffer;
    std::array<char, BUFFER_SIZE> plain_buffer;

    LOGI("udpToTunLoop запущен");

    while (g_running) {
        pollfd pfd{g_udp_fd, POLLIN, 0};
        int prc = poll(&pfd, 1, POLL_TIMEOUT_MS);
        if (prc < 0) {
            if (errno == EINTR) {
                continue;
            }
            failAndStop("poll UDP ошибка");
            break;
        }
        if (prc == 0 || !(pfd.revents & POLLIN)) {
            continue;
        }

        ssize_t n = recv(g_udp_fd, encrypted_buffer.data(), encrypted_buffer.size(), 0);
        if (n < 0) {
            // ECONNREFUSED: сервер ещё не запущен, ICMP port unreachable на прошлую отправку
            if (errno == EINTR || errno == EAGAIN || errno == ECONNREFUSED) {
                continue;
            }
            failAndStop("Ошибка приёма UDP");
            break;
        }
        if (n == 0) {
            continue;
        }

        std::size_t plain_size = 0;
        PacketHeader header{};
        Crypto::Open opened = g_crypto->open(encrypted_buffer.data(), static_cast<std::size_t>(n),
                                             plain_buffer.data(), plain_size, header);
        if (opened == Crypto::Open::OLD_PROTOCOL && !g_old_protocol_logged) {
            g_old_protocol_logged = true;
            LOGW("Сервер отвечает в старом формате (до 0.5.0.0)");
            sendEvent(EV_OLD_PROTOCOL, 0);
        }
        if (opened != Crypto::Open::OK) {
            if (opened == Crypto::Open::AUTH_FAILED || opened == Crypto::Open::MALFORMED) {
                g_dropped.fetch_add(1, std::memory_order_relaxed);
            }
            continue;
        }

        std::optional<KeepAlive> ka;
        if (is_control_packet(plain_buffer.data(), plain_size)) {
            ka = parse_keep_alive(plain_buffer.data(), plain_size);
            if (!ka) {
                g_dropped.fetch_add(1, std::memory_order_relaxed);
                continue;
            }
        }

        const bool had_session = g_peer.has_session();
        PeerState::Verdict v = g_peer.check(header, ka ? std::optional<std::int64_t>(ka->time_ns) : std::nullopt,
                                            wall_clock_ns());
        if (!PeerState::accepted(v)) {
            g_dropped.fetch_add(1, std::memory_order_relaxed);
            if (v == PeerState::Verdict::CLOCK_SKEW && ka && !g_skew_logged) {
                g_skew_logged = true;
                const auto skew_s = static_cast<jlong>((ka->time_ns - wall_clock_ns()) / 1000000000LL);
                LOGW("Keep-alive сервера отклонён: расхождение часов %lld с, допустимо %lld с",
                     static_cast<long long>(skew_s), static_cast<long long>(MAX_CLOCK_SKEW.count()));
                sendEvent(EV_CLOCK_SKEW, skew_s);
            } else if (v == PeerState::Verdict::STALE_SESSION && !g_stale_logged) {
                g_stale_logged = true;
                LOGW("Keep-alive сервера отклонён: сессия начата раньше текущей");
            }
            continue;
        }

        if (v == PeerState::Verdict::NEW_SESSION) {
            if (g_no_response_reported.exchange(false)) {
                LOGI("Связь с сервером восстановлена");
                sendEvent(EV_TUNNEL_RESTORED, 0);
            } else if (had_session) {
                LOGI("Сервер перезапущен, туннель восстановлен");
                sendEvent(EV_TUNNEL_RESTARTED, 0);
            } else {
                LOGI("Туннель установлен");
                sendEvent(EV_TUNNEL_UP, 0);
            }
        } else if (ka && g_no_response_reported.exchange(false, std::memory_order_relaxed)) {
            // ACCEPT в той же сессии после тишины: сервер не забыл клиента, сессию не меняли
            LOGI("Связь с сервером восстановлена");
            sendEvent(EV_TUNNEL_RESTORED, 0);
        }

        if (ka) {
            g_server_seen_ns.store(steady_ns(), std::memory_order_relaxed);
            continue;
        }

        if (!parse_ipv4_packet(plain_buffer.data(), plain_size)) {
            continue;
        }

        ssize_t written = write(g_tun_fd, plain_buffer.data(), plain_size);
        if (written >= 0) {
            g_rx_packets.fetch_add(1, std::memory_order_relaxed);
            g_rx_bytes.fetch_add(static_cast<unsigned long long>(written), std::memory_order_relaxed);
        } else if (errno != EINTR && errno != EAGAIN) {
            LOGW("Ошибка записи в TUN: errno=%d", errno);
        }
    }

    LOGI("udpToTunLoop остановлен");
}

/** @brief Keep-alive по расписанию, статистика в Kotlin, контроль молчания сервера. */
void controlLoop() {
    LOGI("controlLoop запущен");

    sendKeepAlive();
    const auto started = std::chrono::steady_clock::now();
    auto next_ka = started + KEEP_ALIVE_INTERVAL;
    auto next_stats = started + std::chrono::seconds(1);

    while (g_running) {
        const auto now = std::chrono::steady_clock::now();
        auto timeout = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::min(next_ka, next_stats) - now);
        if (timeout.count() < 0) {
            timeout = std::chrono::milliseconds{0};
        }

        pollfd pfd{g_stop_pipe[0], POLLIN, 0};
        int prc = poll(&pfd, 1, static_cast<int>(timeout.count()));
        if (prc < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        if (prc > 0 && (pfd.revents & POLLIN)) {
            break; // остановка
        }

        const auto after = std::chrono::steady_clock::now();
        if (after >= next_ka) {
            sendKeepAlive();
            next_ka += KEEP_ALIVE_INTERVAL;
        }
        if (after >= next_stats) {
            sendStats();
            next_stats += std::chrono::seconds(1);
        }

        const auto silence = std::chrono::steady_clock::duration(steady_ns() - g_server_seen_ns.load());
        if (silence < SERVER_SILENCE) {
            // флаг не снимаем здесь: только принятый от сервера keep-alive означает,
            // что связь вернулась (снимает его udpToTunLoop и шлёт EV_TUNNEL_RESTORED)
            continue;
        }
        if (!g_no_response_reported.exchange(true)) {
            LOGW("Сервер не отвечает на keep-alive больше %lld с",
                 static_cast<long long>(SERVER_SILENCE.count()));
            sendEvent(EV_NO_RESPONSE, 0);
        }
    }

    LOGI("controlLoop остановлен");
}

void closeFd(int& fd) {
    if (fd >= 0) {
        close(fd);
        fd = -1;
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_vpn_MyVpnService_nativeStart(
        JNIEnv* env,
        jobject thiz,
        jint tunFd,
        jstring serverIp,
        jint serverPort,
        jstring clientIp,
        jstring key
) {
    LOGI("nativeStart вызван, tunFd=%d", tunFd);

    if (g_running) {
        LOGI("nativeStart: уже запущено");
        return JNI_TRUE;
    }

    if (sodium_init() < 0) {
        LOGE("Не удалось инициализировать libsodium");
        return JNI_FALSE;
    }

    const char* server_ip_c = env->GetStringUTFChars(serverIp, nullptr);
    const char* client_ip_c = env->GetStringUTFChars(clientIp, nullptr);
    const char* key_c = env->GetStringUTFChars(key, nullptr);

    if (!server_ip_c || !client_ip_c || !key_c) {
        LOGE("Не удалось получить строковые параметры");
        if (server_ip_c) env->ReleaseStringUTFChars(serverIp, server_ip_c);
        if (client_ip_c) env->ReleaseStringUTFChars(clientIp, client_ip_c);
        if (key_c) env->ReleaseStringUTFChars(key, key_c);
        return JNI_FALSE;
    }

    // сессия собеседника живёт между запусками: сервер может помнить клиента из
    // прошлого подключения и отвечать ACCEPT в старой сессии, без NEW_SESSION
    // EV_TUNNEL_UP не пришёл бы. Поэтому состояние сбрасываем при каждом старте
    g_peer = PeerState{};

    g_service_obj = env->NewGlobalRef(thiz);

    auto releaseParams = [&] {
        env->ReleaseStringUTFChars(serverIp, server_ip_c);
        env->ReleaseStringUTFChars(clientIp, client_ip_c);
        env->ReleaseStringUTFChars(key, key_c);
    };

    // любая ошибка до старта потоков: закрыть созданное, освободить ссылки
    auto failStart = [&](const char* what) -> jboolean {
        LOGE("%s", what);
        closeFd(g_udp_fd);
        closeFd(g_tun_fd);
        g_crypto.reset();
        g_tx.reset();
        g_peer = PeerState{};
        if (g_service_obj) {
            env->DeleteGlobalRef(g_service_obj);
            g_service_obj = nullptr;
        }
        releaseParams();
        return JNI_FALSE;
    };

    if (inet_pton(AF_INET, client_ip_c, &g_tun_ip_be) != 1) {
        return failStart("Некорректный IP клиента в туннеле");
    }

    std::memset(&g_server_addr, 0, sizeof(g_server_addr));
    g_server_addr.sin_family = AF_INET;
    g_server_addr.sin_port = htons(static_cast<std::uint16_t>(serverPort));
    if (inet_pton(AF_INET, server_ip_c, &g_server_addr.sin_addr) != 1) {
        return failStart("Некорректный IP сервера");
    }

    g_tun_fd = dup(tunFd);
    if (g_tun_fd < 0) {
        return failStart("dup(tunFd) ошибка");
    }

    g_udp_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (g_udp_fd < 0) {
        return failStart("socket UDP ошибка");
    }

    if (!protectSocketFromJava(g_udp_fd)) {
        return failStart("protectSocket вернул false");
    }

    if (connect(g_udp_fd, reinterpret_cast<sockaddr*>(&g_server_addr), sizeof(g_server_addr)) != 0) {
        return failStart("connect UDP ошибка");
    }

    if (pipe(g_stop_pipe) != 0) {
        return failStart("pipe ошибка");
    }

    g_crypto = std::make_unique<Crypto>(std::string_view(key_c), Direction::CLIENT_TO_SERVER);
    g_tx = std::make_unique<TxSession>();

    LOGI("UDP-сокет создан и защищён от VPN, server=%s:%d", server_ip_c, serverPort);

    releaseParams();

    g_tx_packets = 0;
    g_tx_bytes = 0;
    g_rx_packets = 0;
    g_rx_bytes = 0;
    g_dropped = 0;
    g_no_response_reported = false;
    g_old_protocol_logged = false;
    g_skew_logged = false;
    g_stale_logged = false;
    g_server_seen_ns.store(steady_ns(), std::memory_order_relaxed);

    g_running = true;

    g_tun_to_udp_thread = std::thread(tunToUdpLoop);
    g_udp_to_tun_thread = std::thread(udpToTunLoop);
    g_control_thread = std::thread(controlLoop);

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_vpn_MyVpnService_nativeStop(JNIEnv* env, jobject) {
    LOGI("nativeStop вызван");

    if (!g_running && g_tun_fd < 0 && g_udp_fd < 0) {
        LOGI("nativeStop: уже остановлено");
        return;
    }

    g_running = false;
    requestStop();

    if (g_tun_to_udp_thread.joinable()) {
        g_tun_to_udp_thread.join();
    }
    if (g_udp_to_tun_thread.joinable()) {
        g_udp_to_tun_thread.join();
    }
    if (g_control_thread.joinable()) {
        g_control_thread.join();
    }

    // дескрипторы закрываются только после join: потоки ими пользуются
    closeFd(g_stop_pipe[0]);
    closeFd(g_stop_pipe[1]);
    closeFd(g_udp_fd);
    closeFd(g_tun_fd);

    g_crypto.reset();
    g_tx.reset();

    if (g_service_obj) {
        env->DeleteGlobalRef(g_service_obj);
        g_service_obj = nullptr;
    }

    LOGI("nativeStop завершён");
}
