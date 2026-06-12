/*
 * uinput_helper.c — MULTI-DEVICE PROXY + GRAB
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <dirent.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/epoll.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <linux/input.h>
#include <linux/uinput.h>

#define PORT 19999
#define MAX_MAPPINGS 100

#define MAP_BUTTON 1
#define MAP_AXIS 2
#define MAP_DPAD_ANALOG 3
#define MAP_DPAD_HAT 4

typedef struct {
    int keycode;
    int type;
    int gamepad_code;
    int axis_value;
} KeyMapping;

KeyMapping mappings[MAX_MAPPINGS];
int n_mappings = 0;

int uinput_fd = -1;
int kbd_fd = -1;
int passthrough_fd = -1;  // Dispositivo para reinyectar teclas sin mapeo
pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

// =========================================================
// LIMPIEZA GARANTIZADA AL RECIBIR SIGTERM / SIGINT
// =========================================================

static void handle_sigterm(int sig) {
    (void)sig;
    if (kbd_fd >= 0) {
        ioctl(kbd_fd, EVIOCGRAB, 0);
        close(kbd_fd);
        kbd_fd = -1;
    }
    if (passthrough_fd >= 0) {
        ioctl(passthrough_fd, UI_DEV_DESTROY);
        close(passthrough_fd);
        passthrough_fd = -1;
    }
    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
    }
    fprintf(stderr, "uinput_helper: limpieza por señal, saliendo\n");
    _exit(0);
}

static int dpad_up=0, dpad_down=0, dpad_left=0, dpad_right=0;
static int hat_up=0, hat_down=0, hat_left=0, hat_right=0;

// =========================================================
// SETUP DISPOSITIVOS VIRTUALES
// =========================================================

// 1. GAMEPAD XBOX 360
static int setup_gamepad(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    // Botones Xbox (Eliminados 0x138 y 0x139 porque son exclusivamente ejes)
    int btns[] = {0x130,0x131,0x133,0x134,0x136,0x137,0x13A,0x13B,0x13C,0x13D,0x13E};
    for (int i=0;i<11;i++) ioctl(fd, UI_SET_KEYBIT, btns[i]);

    // Palancas
    ioctl(fd, UI_SET_ABSBIT, ABS_X); ioctl(fd, UI_SET_ABSBIT, ABS_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_RX); ioctl(fd, UI_SET_ABSBIT, ABS_RY);
    struct uinput_abs_setup sticks = {0};
    sticks.absinfo.minimum = -32768; sticks.absinfo.maximum = 32767;
    sticks.absinfo.flat = 128; sticks.absinfo.fuzz = 16;
    sticks.code = ABS_X; ioctl(fd, UI_ABS_SETUP, &sticks);
    sticks.code = ABS_Y; ioctl(fd, UI_ABS_SETUP, &sticks);
    sticks.code = ABS_RX; ioctl(fd, UI_ABS_SETUP, &sticks);
    sticks.code = ABS_RY; ioctl(fd, UI_ABS_SETUP, &sticks);

    // Gatillos (CORREGIDO: 0 a 255)
    ioctl(fd, UI_SET_ABSBIT, ABS_Z); ioctl(fd, UI_SET_ABSBIT, ABS_RZ);
    struct uinput_abs_setup triggers = {0};
    triggers.absinfo.minimum = 0; triggers.absinfo.maximum = 255;
    triggers.absinfo.flat = 0; triggers.absinfo.fuzz = 0;
    triggers.code = ABS_Z; ioctl(fd, UI_ABS_SETUP, &triggers);
    triggers.code = ABS_RZ; ioctl(fd, UI_ABS_SETUP, &triggers);

    // D-Pad Digital (HAT)
    ioctl(fd, UI_SET_ABSBIT, ABS_HAT0X); ioctl(fd, UI_SET_ABSBIT, ABS_HAT0Y);
    struct uinput_abs_setup hat = {0};
    hat.absinfo.minimum = -1; hat.absinfo.maximum = 1;
    hat.code = ABS_HAT0X; ioctl(fd, UI_ABS_SETUP, &hat);
    hat.code = ABS_HAT0Y; ioctl(fd, UI_ABS_SETUP, &hat);

    struct uinput_setup usetup={0};
    usetup.id.bustype=BUS_VIRTUAL; usetup.id.vendor=0x045E; usetup.id.product=0x028E; usetup.id.version=1;
    strncpy(usetup.name,"T9 Gamepad Virtual",UINPUT_MAX_NAME_SIZE-1);
    ioctl(fd,UI_DEV_SETUP,&usetup);
    ioctl(fd,UI_DEV_CREATE);
    
    fprintf(stderr,"uinput_helper: Gamepad Xbox creado fd=%d\n",fd);
    return fd;
}

// 2. TECLADO VIRTUAL PC
static int setup_keyboard(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    // Habilitar códigos de teclas de PC
    for (int i=1; i<KEY_MAX; i++) {
        ioctl(fd, UI_SET_KEYBIT, i);
    }

    struct uinput_setup usetup={0};
    usetup.id.bustype=BUS_VIRTUAL; usetup.id.vendor=0x1234; usetup.id.product=0x5678; usetup.id.version=1;
    strncpy(usetup.name,"T9 Keyboard Virtual",UINPUT_MAX_NAME_SIZE-1);
    ioctl(fd,UI_DEV_SETUP,&usetup);
    ioctl(fd,UI_DEV_CREATE);
    
    fprintf(stderr,"uinput_helper: Teclado Virtual creado fd=%d\n",fd);
    return fd;
}

// 3. TECLADO PASSTHROUGH — reinyecta teclas no mapeadas al sistema
// BUS_USB + vendor/product genérico para que Android lo clasifique como teclado puro,
// no como gamepad. Solo registramos los keycodes que necesitamos pasar al sistema.
static int setup_passthrough(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    // Solo keycodes de sistema — NO registrar botones de gamepad (0x130+)
    // para que Android no lo clasifique como GAMEPAD/JOYSTICK
    int sys_keys[] = {
        KEY_BACK,        // 158 — Atrás
        KEY_POWER,       // 116 — Power/Bloquear
        KEY_HOME,        // 102 — Home
        KEY_MENU,        // 139 — Menú
        KEY_VOLUMEUP,    // 115 — Volumen +
        KEY_VOLUMEDOWN,  // 114 — Volumen -
        KEY_PHONE,       // 169 — Llamar
        // Teclas T9 numéricas por si el perfil no las mapea todas
        KEY_0, KEY_1, KEY_2, KEY_3, KEY_4,
        KEY_5, KEY_6, KEY_7, KEY_8, KEY_9,
        // Flechas
        KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT,
        KEY_OK,          // 353 — Centro/OK
    };
    for (int i = 0; i < (int)(sizeof(sys_keys)/sizeof(sys_keys[0])); i++) {
        ioctl(fd, UI_SET_KEYBIT, sys_keys[i]);
    }

    struct uinput_setup usetup = {0};
    usetup.id.bustype = BUS_USB;      // USB hace que Android lo trate como teclado externo
    usetup.id.vendor  = 0x0001;
    usetup.id.product = 0x0001;
    usetup.id.version = 1;
    strncpy(usetup.name, "T9 Passthrough Keys", UINPUT_MAX_NAME_SIZE - 1);
    ioctl(fd, UI_DEV_SETUP, &usetup);
    ioctl(fd, UI_DEV_CREATE);
    fprintf(stderr, "uinput_helper: Passthrough creado fd=%d\n", fd);
    return fd;
}

static void destroy_uinput_device(void) {
    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
        fprintf(stderr, "uinput_helper: Dispositivo virtual destruido\n");
    }
}

// =========================================================
// PROCESAMIENTO DE EVENTOS
// =========================================================

static void emit_ev(int type, int code, int val) {
    if (uinput_fd < 0) return;
    struct input_event ie={0};
    ie.type=type; ie.code=code; ie.value=val;
    write(uinput_fd, &ie, sizeof(ie));
}

static void process_key(int keycode, int action) {
    pthread_mutex_lock(&lock);
    fprintf(stderr, "uinput_helper: key=%d action=%d\n", keycode, action);
    KeyMapping *m = NULL;
    for (int i = 0; i < n_mappings; i++) {
        if (mappings[i].keycode == keycode) { m = &mappings[i]; break; }
    }

    if (m) {
        // Tecla mapeada — emitir al dispositivo virtual (gamepad/teclado)
        int pressed = (action == 1 || action == 2);
        switch (m->type) {
            case MAP_BUTTON:
                if (m->gamepad_code == 0x138) {
                    emit_ev(EV_ABS, ABS_Z, pressed ? 255 : 0);
                    emit_ev(EV_SYN, SYN_REPORT, 0);
                } else if (m->gamepad_code == 0x139) {
                    emit_ev(EV_ABS, ABS_RZ, pressed ? 255 : 0);
                    emit_ev(EV_SYN, SYN_REPORT, 0);
                } else if (m->gamepad_code >= 0x220 && m->gamepad_code <= 0x223) {
                    if (m->gamepad_code == 0x220) hat_up = pressed;
                    else if (m->gamepad_code == 0x221) hat_down = pressed;
                    else if (m->gamepad_code == 0x222) hat_left = pressed;
                    else if (m->gamepad_code == 0x223) hat_right = pressed;
                    int hx = 0, hy = 0;
                    if (hat_right) hx = 1; else if (hat_left) hx = -1;
                    if (hat_down) hy = 1; else if (hat_up) hy = -1;
                    emit_ev(EV_ABS, ABS_HAT0X, hx);
                    emit_ev(EV_ABS, ABS_HAT0Y, hy);
                    emit_ev(EV_SYN, SYN_REPORT, 0);
                } else {
                    emit_ev(EV_KEY, m->gamepad_code, pressed ? 1 : 0);
                    emit_ev(EV_SYN, SYN_REPORT, 0);
                }
                break;
            case MAP_AXIS: {
                int val = pressed ? m->axis_value : 0;
                emit_ev(EV_ABS, m->gamepad_code, val);
                emit_ev(EV_SYN, SYN_REPORT, 0);
                break;
            }
            case MAP_DPAD_ANALOG: {
                if (keycode == 103) dpad_up    = pressed;
                else if (keycode == 108) dpad_down  = pressed;
                else if (keycode == 105) dpad_left  = pressed;
                else if (keycode == 106) dpad_right = pressed;
                int x = 0, y = 0, val = 32767;
                if (dpad_right) x =  val; else if (dpad_left) x = -val;
                if (dpad_down)  y =  val; else if (dpad_up)   y = -val;
                int axis_x = (m->gamepad_code == 1) ? ABS_RX : ABS_X;
                int axis_y = (m->gamepad_code == 1) ? ABS_RY : ABS_Y;
                emit_ev(EV_ABS, axis_x, x);
                emit_ev(EV_ABS, axis_y, y);
                emit_ev(EV_SYN, SYN_REPORT, 0);
                break;
            }
        }
    } else if (passthrough_fd >= 0) {
        fprintf(stderr, "uinput_helper: passthrough key=%d fd=%d\n", keycode, passthrough_fd);
        // Tecla sin mapeo — reinyectar al sistema via passthrough
        // Android procesa KEY_BACK, KEY_POWER, etc. con normalidad
        struct input_event ie = {0};
        ie.type  = EV_KEY;
        ie.code  = keycode;
        ie.value = action;
        write(passthrough_fd, &ie, sizeof(ie));
        ie.type = EV_SYN; ie.code = SYN_REPORT; ie.value = 0;
        write(passthrough_fd, &ie, sizeof(ie));
    }
    pthread_mutex_unlock(&lock);
}

// =========================================================
// LECTURA DEL TECLADO FÍSICO
// =========================================================
int find_keyboard() {
    DIR *dir = opendir("/dev/input");
    struct dirent *ent;
    int found_fd = -1;
    while((ent=readdir(dir))) {
        if(strncmp(ent->d_name,"event",5)==0) {
            char path[256];
            snprintf(path,sizeof(path),"/dev/input/%s",ent->d_name);
            int fd=open(path,O_RDWR);
            if(fd>=0) {
                char name[256];
                ioctl(fd,EVIOCGNAME(sizeof(name)),name);
                if (strstr(name,"mtk-kpd") || strstr(name,"keyboard")) {
                    found_fd = fd;
                    break;
                }
                close(fd);
            }
        }
    }
    closedir(dir);
    return found_fd;
}

void* keyboard_thread(void* arg) {
    while(1) {
        kbd_fd = find_keyboard();
        if(kbd_fd < 0) { sleep(1); continue; }
        
        fprintf(stderr,"uinput_helper: Teclado fisico abierto fd=%d\n", kbd_fd);
        
        struct input_event ev;
        while(read(kbd_fd,&ev,sizeof(ev))>0) {
            if(ev.type==EV_KEY) process_key(ev.code, ev.value);
        }
        close(kbd_fd);
        kbd_fd = -1;
        sleep(1);
    }
    return NULL;
}

// =========================================================
// MAIN & TCP SERVER
// =========================================================
int main(void) {
    fprintf(stderr,"uinput_helper: iniciando daemon root\n");
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, handle_sigterm);
    signal(SIGINT,  handle_sigterm);

    // uinput_fd arranca en -1 — la app debe enviar 0xFC para crear el dispositivo.
    // No creamos nada aquí para evitar dispositivos huérfanos si la app no llega a conectar.

    // El passthrough sí se crea siempre — necesario desde el primer grab.
    passthrough_fd = setup_passthrough();

    pthread_t kbd_tid;
    pthread_create(&kbd_tid,NULL,keyboard_thread,NULL);

    int server = socket(AF_INET,SOCK_STREAM,0);
    int opt=1; setsockopt(server,SOL_SOCKET,SO_REUSEADDR,&opt,sizeof(opt));
    struct sockaddr_in addr={0};
    addr.sin_family=AF_INET; addr.sin_addr.s_addr=inet_addr("127.0.0.1"); addr.sin_port=htons(PORT);
    bind(server,(struct sockaddr*)&addr,sizeof(addr));
    listen(server,1);

    while(1) {
        int sock = accept(server,NULL,NULL);
        if (sock<0) continue;
        
        unsigned char buf[1024];
        while(1) {
            int n = read(sock, buf, 1);
            if (n<=0) break;
            
            switch(buf[0]) {
                case 0x01: { // RESTAURADO: Evento BTN desde gamepad.c
                    int r=0; while(r<3){ int rr=read(sock, buf+1+r, 3-r); if(rr<=0) goto done; r+=rr; }
                    emit_ev(EV_KEY, buf[1] | (buf[2]<<8), buf[3]);
                    emit_ev(EV_SYN, SYN_REPORT, 0);
                    break;
                }
                case 0x02: { // RESTAURADO: Evento ABS desde gamepad.c
                    int r=0; while(r<6){ int rr=read(sock, buf+1+r, 6-r); if(rr<=0) goto done; r+=rr; }
                    int code = buf[1] | (buf[2]<<8);
                    int val = buf[3] | (buf[4]<<8) | (buf[5]<<16) | (buf[6]<<24);
                    emit_ev(EV_ABS, code, val);
                    emit_ev(EV_SYN, SYN_REPORT, 0);
                    break;
                }
                case 0xAA: break; // Ping
                case 0xFC: { // CREAR/DESTRUIR DISPOSITIVO VIRTUAL
                    int r=0; while(r<1){ int rr=read(sock, buf+1+r, 1-r); if(rr<=0) goto done; r+=rr; }
                    int dev_type = buf[1];
                    destroy_uinput_device();
                    if (dev_type == 1) uinput_fd = setup_gamepad();
                    else if (dev_type == 2) uinput_fd = setup_keyboard();
                    break;
                }
                case 0xFD: { // GRAB/UNGRAB TECLADO FÍSICO
                    int r=0; while(r<1){ int rr=read(sock, buf+1+r, 1-r); if(rr<=0) goto done; r+=rr; }
                    int grab_state = buf[1];
                    if (kbd_fd >= 0) {
                        ioctl(kbd_fd, EVIOCGRAB, grab_state);
                        fprintf(stderr,"uinput_helper: GRAB = %d\n", grab_state);
                    }
                    break;
                }
                case 0xFE: { // CARGAR MAPPINGS
                    int r=0; while(r<1){ int rr=read(sock, buf+1+r, 1-r); if(rr<=0) goto done; r+=rr; }
                    int count = buf[1];
                    int expected = count * 9;
                    int recvd=0;
                    while(recvd < expected) {
                        int rr = read(sock, buf+2+recvd, expected-recvd);
                        if(rr<=0) goto done;
                        recvd+=rr;
                    }
                    pthread_mutex_lock(&lock);
                    n_mappings = 0;
                    int ptr=2;
                    for(int i=0;i<count;i++) {
                        mappings[i].keycode = buf[ptr] | (buf[ptr+1]<<8); ptr+=2;
                        mappings[i].gamepad_code = buf[ptr] | (buf[ptr+1]<<8); ptr+=2;
                        mappings[i].type = buf[ptr++];
                        mappings[i].axis_value = buf[ptr] | (buf[ptr+1]<<8) | (buf[ptr+2]<<16) | (buf[ptr+3]<<24); ptr+=4;
                        n_mappings++;
                    }
                    pthread_mutex_unlock(&lock);
                    break;
                }
                case 0xFF: goto done;
            }
        }
done:
        // Si la app se cierra o crashea, soltamos el teclado y destruimos dispositivos
        if (kbd_fd >= 0) ioctl(kbd_fd, EVIOCGRAB, 0);
        destroy_uinput_device();
        if (passthrough_fd >= 0) {
            ioctl(passthrough_fd, UI_DEV_DESTROY);
            close(passthrough_fd);
            passthrough_fd = -1;
        }
        close(sock);
    }
    return 0;
}