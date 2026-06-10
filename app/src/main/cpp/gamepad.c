/*
 * gamepad.c — v2 PROXY
 *
 * Todas las funciones de emit ahora escriben al socket TCP del helper
 * en lugar de directamente a /dev/uinput.
 *
 * Protocolo:
 *   Botón:  [0x01] [code_lo] [code_hi] [value]
 *   Eje:    [0x02] [code_lo] [code_hi] [val_b0] [val_b1] [val_b2] [val_b3]
 *   Reset:  envía ejes en 0
 *   Shutdown: [0xFF]
 */

#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <android/log.h>

#define TAG   "T9GamepadNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Escribir exactamente n bytes al socket */
static int write_exact(int fd, const void *buf, int n) {
    const char *p = (const char*)buf;
    int total = 0;
    while (total < n) {
        int w = write(fd, p + total, n - total);
        if (w <= 0) return -1;
        total += w;
    }
    return 0;
}

/* ── JNI: createDevice — en modo proxy no hace nada (el helper ya creó el dispositivo) ── */
JNIEXPORT jint JNICALL
Java_com_t9mapper_service_GamepadNative_createDevice(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    /* En modo proxy el dispositivo lo crea el helper.
       Devolvemos -1 para indicar que hay que usar connectAndReceiveFd */
    return -1;
}

/* ── JNI: destroyDevice — envía shutdown al helper ── */
JNIEXPORT void JNICALL
Java_com_t9mapper_service_GamepadNative_destroyDevice(JNIEnv *env, jclass clazz, jint fd) {
    (void)env; (void)clazz;
    if (fd < 0) return;
    unsigned char cmd = 0xFF;
    write(fd, &cmd, 1);
    close(fd);
    LOGI("Conexión proxy cerrada fd=%d", fd);
}

/* ── JNI: sendButton ── */
JNIEXPORT void JNICALL
Java_com_t9mapper_service_GamepadNative_sendButton(
        JNIEnv *env, jclass clazz, jint fd, jint btnCode, jboolean pressed) {
    (void)env; (void)clazz;
    if (fd < 0) return;
    unsigned char buf[4];
    buf[0] = 0x01;
    buf[1] = (unsigned char)(btnCode & 0xFF);
    buf[2] = (unsigned char)((btnCode >> 8) & 0xFF);
    buf[3] = pressed ? 1 : 0;
    if (write_exact(fd, buf, 4) < 0)
        LOGE("sendButton write error: %s", strerror(errno));
}

/* ── JNI: sendAxis ── */
JNIEXPORT void JNICALL
Java_com_t9mapper_service_GamepadNative_sendAxis(
        JNIEnv *env, jclass clazz, jint fd, jint axisCode, jint value) {
    (void)env; (void)clazz;
    if (fd < 0) return;
    unsigned char buf[7];
    buf[0] = 0x02;
    buf[1] = (unsigned char)(axisCode & 0xFF);
    buf[2] = (unsigned char)((axisCode >> 8) & 0xFF);
    buf[3] = (unsigned char)(value & 0xFF);
    buf[4] = (unsigned char)((value >> 8) & 0xFF);
    buf[5] = (unsigned char)((value >> 16) & 0xFF);
    buf[6] = (unsigned char)((value >> 24) & 0xFF);
    if (write_exact(fd, buf, 7) < 0)
        LOGE("sendAxis write error: %s", strerror(errno));
}

/* ── JNI: sendDPadAsAnalog ── */
JNIEXPORT void JNICALL
Java_com_t9mapper_service_GamepadNative_sendDPadAsAnalog(
        JNIEnv *env, jclass clazz,
        jint fd, jint direction, jboolean pressed,
        jint mode, jint rampValue) {
    (void)env; (void)clazz;
    if (fd < 0) return;

    int val = (mode == 1) ? rampValue : 32767;
    if (!pressed) val = 0;

    int x = 0, y = 0;
    switch (direction) {
        case 1: y = -val; break;
        case 2: y =  val; break;
        case 3: x = -val; break;
        case 4: x =  val; break;
        case 5: x = -val; y = -val; break;
        case 6: x =  val; y = -val; break;
        case 7: x = -val; y =  val; break;
        case 8: x =  val; y =  val; break;
    }
    if (x >  32767) x =  32767;
    if (x < -32767) x = -32767;
    if (y >  32767) y =  32767;
    if (y < -32767) y = -32767;

    /* Enviar ABS_X */
    unsigned char bx[7];
    bx[0]=0x02; bx[1]=0x00; bx[2]=0x00;
    bx[3]=(unsigned char)(x&0xFF); bx[4]=(unsigned char)((x>>8)&0xFF);
    bx[5]=(unsigned char)((x>>16)&0xFF); bx[6]=(unsigned char)((x>>24)&0xFF);
    write_exact(fd, bx, 7);

    /* Enviar ABS_Y */
    unsigned char by[7];
    by[0]=0x02; by[1]=0x01; by[2]=0x00;
    by[3]=(unsigned char)(y&0xFF); by[4]=(unsigned char)((y>>8)&0xFF);
    by[5]=(unsigned char)((y>>16)&0xFF); by[6]=(unsigned char)((y>>24)&0xFF);
    write_exact(fd, by, 7);
}

/* ── JNI: sendDPadHat ── */
JNIEXPORT void JNICALL
Java_com_t9mapper_service_GamepadNative_sendDPadHat(
        JNIEnv *env, jclass clazz,
        jint fd, jint direction, jboolean pressed) {
    (void)env; (void)clazz;
    if (fd < 0) return;
    int hx = 0, hy = 0;
    if (pressed) {
        switch (direction) {
            case 1: hy=-1; break; case 2: hy=1; break;
            case 3: hx=-1; break; case 4: hx=1; break;
            case 5: hx=-1; hy=-1; break; case 6: hx=1; hy=-1; break;
            case 7: hx=-1; hy=1;  break; case 8: hx=1; hy=1;  break;
        }
    }
    /* ABS_HAT0X = 0x10 */
    unsigned char bx[7] = {0x02,0x10,0x00,
        (unsigned char)(hx&0xFF),(unsigned char)((hx>>8)&0xFF),
        (unsigned char)((hx>>16)&0xFF),(unsigned char)((hx>>24)&0xFF)};
    write_exact(fd, bx, 7);
    /* ABS_HAT0Y = 0x11 */
    unsigned char by[7] = {0x02,0x11,0x00,
        (unsigned char)(hy&0xFF),(unsigned char)((hy>>8)&0xFF),
        (unsigned char)((hy>>16)&0xFF),(unsigned char)((hy>>24)&0xFF)};
    write_exact(fd, by, 7);
}

/* ── JNI: resetAxes ── */
JNIEXPORT void JNICALL
Java_com_t9mapper_service_GamepadNative_resetAxes(JNIEnv *env, jclass clazz, jint fd) {
    (void)env; (void)clazz;
    if (fd < 0) return;
    int axes[] = {0,1,2,3,4,5,0x10,0x11};
    for (int i = 0; i < 8; i++) {
        unsigned char buf[7] = {0x02,
            (unsigned char)(axes[i]&0xFF),(unsigned char)((axes[i]>>8)&0xFF),
            0,0,0,0};
        write_exact(fd, buf, 7);
    }
}

/* ── JNI: checkUinputAccess — siempre true en modo proxy ── */
JNIEXPORT jint JNICALL
Java_com_t9mapper_service_GamepadNative_checkUinputAccess(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return 1;
}
