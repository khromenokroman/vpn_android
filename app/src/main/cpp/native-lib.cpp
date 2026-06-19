#include <android/log.h>
#include <arpa/inet.h>
#include <jni.h>
#include <netinet/in.h>
#include <sodium.h>
#include <string>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <thread>

#include "crypto.hpp"

#define LOG_TAG "NativeVPN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::atomic<bool> g_running{false};

static std::thread g_tun_to_udp_thread;
static std::thread g_udp_to_tun_thread;

static int g_tun_fd = -1;
static int g_udp_fd = -1;

static sockaddr_in g_server_addr{};
static JavaVM* g_vm = nullptr;
static jobject g_service_obj = nullptr;

static std::unique_ptr<Crypto> g_crypto;

static bool protectSocketFromJava(int fd) {
    if (!g_vm || !g_service_obj) {
        LOGE("protectSocketFromJava: нет JavaVM или service object");
        return false;
    }

    JNIEnv* env = nullptr;
    bool attached = false;

    jint getEnvResult = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("Не удалось AttachCurrentThread");
            return false;
        }
        attached = true;
    } else if (getEnvResult != JNI_OK) {
        LOGE("GetEnv вернул ошибку");
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

    if (attached) {
        g_vm->DetachCurrentThread();
    }

    return result == JNI_TRUE;
}

static void tunToUdpLoop() {
    char plain_buffer[65536];
    char encrypted_buffer[65536 + 128];

    LOGI("tunToUdpLoop запущен, tunFd=%d, udpFd=%d", g_tun_fd, g_udp_fd);

    while (g_running) {
        ssize_t n = read(g_tun_fd, plain_buffer, sizeof(plain_buffer));

        if (n > 0) {
            std::size_t encrypted_size = 0;

            if (!g_crypto || !g_crypto->encrypt(plain_buffer, static_cast<std::size_t>(n), encrypted_buffer, encrypted_size)) {
                LOGE("Ошибка шифрования пакета");
                continue;
            }

            ssize_t sent = send(
                    g_udp_fd,
                    encrypted_buffer,
                    encrypted_size,
                    0
            );

            if (sent > 0) {
                LOGI("TUN->UDP encrypted: plain=%zd, encrypted=%zu, sent=%zd", n, encrypted_size, sent);
            } else if (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) {
                LOGE("send ошибка, errno=%d", errno);
            }
        } else if (n == 0) {
            LOGI("read TUN вернул 0");
            break;
        } else {
            if (errno == EINTR) {
                continue;
            }

            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }

            if (g_running) {
                LOGE("Ошибка чтения из TUN, errno=%d", errno);
            }
            break;
        }
    }

    LOGI("tunToUdpLoop остановлен");
}

static void udpToTunLoop() {
    char encrypted_buffer[65536 + 128];
    char plain_buffer[65536];

    LOGI("udpToTunLoop запущен, udpFd=%d, tunFd=%d", g_udp_fd, g_tun_fd);

    while (g_running) {
        ssize_t n = recv(g_udp_fd, encrypted_buffer, sizeof(encrypted_buffer), 0);

        if (n > 0) {
            std::size_t plain_size = 0;

            if (!g_crypto || !g_crypto->decrypt(encrypted_buffer, static_cast<std::size_t>(n), plain_buffer, plain_size)) {
                LOGE("Ошибка расшифровки пакета, size=%zd", n);
                continue;
            }

            ssize_t written = write(g_tun_fd, plain_buffer, plain_size);

            if (written > 0) {
                LOGI("UDP->TUN decrypted: encrypted=%zd, plain=%zu, written=%zd", n, plain_size, written);
            } else if (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) {
                LOGE("write TUN ошибка, errno=%d", errno);
            }
        } else if (n == 0) {
            continue;
        } else {
            if (errno == EINTR) {
                continue;
            }

            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }

            if (g_running) {
                LOGE("Ошибка чтения UDP, errno=%d", errno);
            }
            break;
        }
    }

    LOGI("udpToTunLoop остановлен");
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_vpn_MainActivity_stringFromJNI(JNIEnv* env, jobject) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_vpn_MyVpnService_nativeStart(
        JNIEnv* env,
        jobject thiz,
        jint tunFd,
        jstring serverIp,
        jint serverPort,
        jstring key
) {
    LOGI("nativeStart вызван, tunFd=%d", tunFd);

    if (g_running) {
        LOGI("nativeStart: уже запущено");
        return;
    }

    if (sodium_init() < 0) {
        LOGE("Не удалось инициализировать libsodium");
        return;
    }

    const char* server_ip_c = env->GetStringUTFChars(serverIp, nullptr);
    const char* key_c = env->GetStringUTFChars(key, nullptr);

    if (!server_ip_c || !key_c) {
        LOGE("Не удалось получить serverIp или key");
        if (server_ip_c) env->ReleaseStringUTFChars(serverIp, server_ip_c);
        if (key_c) env->ReleaseStringUTFChars(key, key_c);
        return;
    }

    g_service_obj = env->NewGlobalRef(thiz);

    g_crypto = std::make_unique<Crypto>(std::string_view(key_c));

    g_tun_fd = dup(tunFd);
    if (g_tun_fd < 0) {
        LOGE("dup(tunFd) ошибка, errno=%d", errno);
        env->ReleaseStringUTFChars(serverIp, server_ip_c);
        env->ReleaseStringUTFChars(key, key_c);
        return;
    }

    g_udp_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (g_udp_fd < 0) {
        LOGE("socket UDP ошибка, errno=%d", errno);
        close(g_tun_fd);
        g_tun_fd = -1;
        env->ReleaseStringUTFChars(serverIp, server_ip_c);
        env->ReleaseStringUTFChars(key, key_c);
        return;
    }

    if (!protectSocketFromJava(g_udp_fd)) {
        LOGE("protectSocket вернул false");
        close(g_udp_fd);
        close(g_tun_fd);
        g_udp_fd = -1;
        g_tun_fd = -1;
        env->ReleaseStringUTFChars(serverIp, server_ip_c);
        env->ReleaseStringUTFChars(key, key_c);
        return;
    }

    std::memset(&g_server_addr, 0, sizeof(g_server_addr));
    g_server_addr.sin_family = AF_INET;
    g_server_addr.sin_port = htons(static_cast<uint16_t>(serverPort));

    if (inet_pton(AF_INET, server_ip_c, &g_server_addr.sin_addr) != 1) {
        LOGE("Некорректный IP сервера: %s", server_ip_c);
        close(g_udp_fd);
        close(g_tun_fd);
        g_udp_fd = -1;
        g_tun_fd = -1;
        env->ReleaseStringUTFChars(serverIp, server_ip_c);
        env->ReleaseStringUTFChars(key, key_c);
        return;
    }
    if (connect(g_udp_fd,
                reinterpret_cast<sockaddr*>(&g_server_addr),
                sizeof(g_server_addr)) != 0) {
        LOGE("connect UDP ошибка, errno=%d", errno);
        close(g_udp_fd);
        close(g_tun_fd);
        g_udp_fd = -1;
        g_tun_fd = -1;
        env->ReleaseStringUTFChars(serverIp, server_ip_c);
        env->ReleaseStringUTFChars(key, key_c);
        return;
    }
    LOGI("UDP-сокет создан и защищён от VPN, server=%s:%d", server_ip_c, serverPort);
    LOGI("Crypto инициализирован");

    env->ReleaseStringUTFChars(serverIp, server_ip_c);
    env->ReleaseStringUTFChars(key, key_c);

    g_running = true;

    g_tun_to_udp_thread = std::thread(tunToUdpLoop);
    g_udp_to_tun_thread = std::thread(udpToTunLoop);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_vpn_MyVpnService_nativeStop(JNIEnv* env, jobject) {
    LOGI("nativeStop вызван");

    if (!g_running) {
        LOGI("nativeStop: уже остановлено");
        return;
    }

    g_running = false;

    if (g_tun_fd >= 0) {
        close(g_tun_fd);
        g_tun_fd = -1;
    }

    if (g_udp_fd >= 0) {
        shutdown(g_udp_fd, SHUT_RDWR);
        close(g_udp_fd);
        g_udp_fd = -1;
    }

    if (g_tun_to_udp_thread.joinable()) {
        g_tun_to_udp_thread.join();
    }

    if (g_udp_to_tun_thread.joinable()) {
        g_udp_to_tun_thread.join();
    }

    g_crypto.reset();

    if (g_service_obj) {
        env->DeleteGlobalRef(g_service_obj);
        g_service_obj = nullptr;
    }

    LOGI("nativeStop завершён");
}