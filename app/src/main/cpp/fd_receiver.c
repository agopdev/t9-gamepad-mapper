/*
 * fd_receiver.c — v6 PROXY
 *
 * Conecta al helper daemon por TCP y devuelve el socket.
 * Todos los eventos se envían por ese socket al helper,
 * que los escribe en /dev/uinput como root.
 *
 * gamepad.c sigue funcionando igual pero ahora escribe
 * al socket TCP en vez de a /dev/uinput directamente.
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>

#define TAG  "T9FdReceiver"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define PORT 19999

/*
 * JNI: Conectar al helper daemon y devolver el socket fd.
 * Este fd se usa como "proxy fd" — los eventos se escriben aquí
 * y el helper los reenvía a /dev/uinput.
 *
 * @return socket fd > 0 si éxito, negativo si error
 */
JNIEXPORT jint JNICALL
Java_com_t9mapper_service_GamepadNative_connectAndReceiveFd(
        JNIEnv *env, jclass clazz, jstring jSocketPath) {
    (void)env; (void)clazz; (void)jSocketPath;

    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) { LOGE("socket: %s", strerror(errno)); return -1; }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family      = AF_INET;
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    addr.sin_port        = htons(PORT);

    if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("connect 127.0.0.1:%d: %s", PORT, strerror(errno));
        close(sock);
        return -2;
    }

    LOGI("Conectado al helper daemon en puerto %d, socket fd=%d", PORT, sock);
    return sock;
}

/* Stub para compatibilidad */
JNIEXPORT jint JNICALL
Java_com_t9mapper_service_GamepadNative_createDeviceViaHelper(
        JNIEnv *env, jclass clazz, jstring a, jstring b) {
    (void)env; (void)clazz; (void)a; (void)b;
    return -99;
}

/*
 * JNI: Enviar bytes raw al socket del helper.
 */
JNIEXPORT jint JNICALL
Java_com_t9mapper_service_GamepadNative_sendRawToSocket(
        JNIEnv *env, jclass clazz, jint socketFd, jbyteArray data) {
    if (socketFd < 0) return -1;
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    int written = (int)write(socketFd, buf, len);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return written;
}
