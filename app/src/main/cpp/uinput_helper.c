/*
 * uinput_helper.c — v7 INPUT READER + GAMEPAD PROXY
 *
 * Como daemon root:
 * 1. Crea el gamepad virtual en /dev/uinput
 * 2. Lee eventos del teclado físico desde /dev/input/eventX
 * 3. Escucha comandos de la app via TCP 127.0.0.1:19999
 * 4. Traduce keycodes → eventos de gamepad según el mapping recibido
 *
 * Protocolo TCP de la app al helper:
 *   [0xAA] = PING (keepalive)
 *   [0x01][code_lo][code_hi][value] = BTN event
 *   [0x02][code_lo][code_hi][v0][v1][v2][v3] = ABS event
 *   [0xFE][n_mappings][keycode][btn_lo][btn_hi][type]... = cargar mappings
 *   [0xFF] = SHUTDOWN
 *
 * Protocolo del helper a la app:
 *   [0x10][keycode][action] = tecla física presionada (para que la app sepa)
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
#include <linux/uinput.h>
#include <linux/input.h>

#define PORT         19999
#define MAX_MAPPINGS 64
#define MAX_INPUTS   8

/* Tipos de mapeo */
#define MAP_BUTTON      1
#define MAP_DPAD_ANALOG 2
#define MAP_DPAD_HAT    3
#define MAP_AXIS        4

typedef struct {
    int      keycode;
    int      gamepad_code;
    int      type;          /* MAP_* */
    int      axis_value;
} KeyMapping;

/* Estado global */
static int       uinput_fd   = -1;
static int       client_sock = -1;
static KeyMapping mappings[MAX_MAPPINGS];
static int       n_mappings  = 0;

/* D-Pad state */
static int dpad_up=0, dpad_down=0, dpad_left=0, dpad_right=0;
static int hat_up=0, hat_down=0, hat_left=0, hat_right=0;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

/* ── emit a uinput event ── */
static void emit_ev(int type, int code, int value) {
    struct input_event ev = {0};
    ev.type  = (unsigned short)type;
    ev.code  = (unsigned short)code;
    ev.value = value;
    write(uinput_fd, &ev, sizeof(ev));
}

/* ── setup gamepad ── */
static int setup_gamepad(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) { perror("open /dev/uinput"); return -1; }

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    int btns[] = {0x130,0x131,0x133,0x134,0x136,0x137,0x138,0x139,0x13A,0x13B,0x13C,0x13D,0x13E,0x220,0x221,0x222,0x223};
    for (int i=0;i<17;i++) ioctl(fd, UI_SET_KEYBIT, btns[i]);
    for (int a=ABS_X;a<=ABS_RZ;a++) ioctl(fd, UI_SET_ABSBIT, a);
    ioctl(fd, UI_SET_ABSBIT, ABS_HAT0X);
    ioctl(fd, UI_SET_ABSBIT, ABS_HAT0Y);

    struct uinput_abs_setup abs = {0};
    abs.absinfo.minimum=-32767; abs.absinfo.maximum=32767;
    abs.absinfo.flat=128; abs.absinfo.fuzz=16;
    for (int a=ABS_X;a<=ABS_RZ;a++) { abs.code=(unsigned short)a; ioctl(fd,UI_ABS_SETUP,&abs); }
    struct uinput_abs_setup hat={0}; hat.absinfo.minimum=-1; hat.absinfo.maximum=1;
    hat.code=ABS_HAT0X; ioctl(fd,UI_ABS_SETUP,&hat);
    hat.code=ABS_HAT0Y; ioctl(fd,UI_ABS_SETUP,&hat);

    struct uinput_setup usetup={0};
    usetup.id.bustype=BUS_VIRTUAL; usetup.id.vendor=0x045E;
    usetup.id.product=0x028E; usetup.id.version=1;
    strncpy(usetup.name,"T9 Gamepad Mapper",UINPUT_MAX_NAME_SIZE-1);
    ioctl(fd,UI_DEV_SETUP,&usetup);
    ioctl(fd,UI_DEV_CREATE);

    fprintf(stderr,"uinput_helper: gamepad creado fd=%d\n",fd);
    return fd;
}


/* ── process a physical key event ── */
/* Keycodes del kernel (Linux input.h) para DuoQin F22 Pro:
 *   UP=103(0x67)  DOWN=108(0x6c)  LEFT=105(0x69)  RIGHT=106(0x6a)
 *   1=2  2=3  3=4  4=5  5=6  6=7  7=8  8=9  9=10  0=11
 *   *=522(0x20a)  #=523(0x20b)  Menu=139(0x8b)  Back=158(0x9e) Call=169(0xa9)
 */
#define KC_UP    103
#define KC_DOWN  108
#define KC_LEFT  105
#define KC_RIGHT 106

static void process_key(int keycode, int action) {
    pthread_mutex_lock(&lock);

    /* Notificar a la app (si está conectada) */
    if (client_sock > 0) {
        unsigned char msg[3] = {0x10, (unsigned char)(keycode & 0xFF), (unsigned char)action};
        write(client_sock, msg, 3);
    }

    /* Buscar mapping */
    KeyMapping *m = NULL;
    for (int i=0;i<n_mappings;i++) {
        if (mappings[i].keycode == keycode) { m = &mappings[i]; break; }
    }

    if (m) {
        int pressed = (action == 1);
        switch (m->type) {
            case MAP_BUTTON:
                // 1. Interceptar gatillos (LT=0x138, RT=0x139) y convertirlos a ejes (0 a 32767)
                if (m->gamepad_code == 0x138) { // LT
                    emit_ev(EV_ABS, ABS_Z, pressed ? 32767 : 0);
                    emit_ev(EV_SYN, SYN_REPORT, 0);
                } else if (m->gamepad_code == 0x139) { // RT
                    emit_ev(EV_ABS, ABS_RZ, pressed ? 32767 : 0);
                    emit_ev(EV_SYN, SYN_REPORT, 0);
                } 
                // 2. Interceptar D-Pad digital (convertido a HAT)
                else if (m->gamepad_code >= 0x220 && m->gamepad_code <= 0x223) {
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
                } 
                // 3. Botones normales
                else {
                    emit_ev(EV_KEY, m->gamepad_code, pressed ? 1 : 0);
                    emit_ev(EV_SYN, SYN_REPORT, 0);
                }
                break;
            case MAP_DPAD_ANALOG: {
                if (keycode==KC_UP)    dpad_up=pressed;
                else if (keycode==KC_DOWN)  dpad_down=pressed;
                else if (keycode==KC_LEFT)  dpad_left=pressed;
                else if (keycode==KC_RIGHT) dpad_right=pressed;
                int x=0, y=0, val=32767;
                if (dpad_right) x=val; else if (dpad_left) x=-val;
                if (dpad_down)  y=val; else if (dpad_up)   y=-val;
                // gamepad_code: 0=LS (ABS_X/ABS_Y), 1=RS (ABS_RX/ABS_RY)
                int axis_x = (m->gamepad_code == 1) ? ABS_RX : ABS_X;
                int axis_y = (m->gamepad_code == 1) ? ABS_RY : ABS_Y;
                emit_ev(EV_ABS, axis_x, x);
                emit_ev(EV_ABS, axis_y, y);
                emit_ev(EV_SYN, SYN_REPORT, 0);
                break;
            }
            case MAP_DPAD_HAT: {
                if (keycode==KC_UP)    hat_up=pressed;
                else if (keycode==KC_DOWN)  hat_down=pressed;
                else if (keycode==KC_LEFT)  hat_left=pressed;
                else if (keycode==KC_RIGHT) hat_right=pressed;

                int hx=0,hy=0;
                if (hat_right) hx=1; else if (hat_left) hx=-1;
                if (hat_down) hy=1; else if (hat_up) hy=-1;

                emit_ev(EV_ABS, ABS_HAT0X, hx);
                emit_ev(EV_ABS, ABS_HAT0Y, hy);
                emit_ev(EV_SYN, SYN_REPORT, 0);
                break;
            }
            case MAP_AXIS: {
                int val = pressed ? m->axis_value : 0;
                emit_ev(EV_ABS, m->gamepad_code, val);
                emit_ev(EV_SYN, SYN_REPORT, 0);
                break;
            }
        }
    }

    pthread_mutex_unlock(&lock);
}

/* ── keyboard reader thread ── */
static int kbd_fds[MAX_INPUTS];
static int n_kbd = 0;

static void* keyboard_thread(void *arg) {
    (void)arg;
    /* Abrir todos los dispositivos de entrada disponibles */
    DIR *d = opendir("/dev/input");
    if (!d) return NULL;
    struct dirent *e;
    while ((e=readdir(d))!=NULL && n_kbd < MAX_INPUTS) {
        if (strncmp(e->d_name,"event",5)!=0) continue;
        char path[64]; snprintf(path,sizeof(path),"/dev/input/%s",e->d_name);
        int fd = open(path,O_RDONLY);
        if (fd<0) continue;
        char name[256]=""; ioctl(fd,EVIOCGNAME(sizeof(name)),name);
        if (strstr(name,"T9 Gamepad")!=NULL) { close(fd); continue; }
        kbd_fds[n_kbd++] = fd;
        fprintf(stderr,"uinput_helper: monitoreando %s (%s)\n",path,name);
    }
    closedir(d);

    /* epoll para leer de todos */
    int epfd = epoll_create1(0);
    for (int i=0;i<n_kbd;i++) {
        struct epoll_event ev = {.events=EPOLLIN, .data.fd=kbd_fds[i]};
        epoll_ctl(epfd,EPOLL_CTL_ADD,kbd_fds[i],&ev);
    }

    struct epoll_event events[16];
    while (1) {
        int n = epoll_wait(epfd,events,16,100);
        for (int i=0;i<n;i++) {
            struct input_event iev;
            int fd = events[i].data.fd;
            while (read(fd,&iev,sizeof(iev))>0) {
                if (iev.type==EV_KEY && (iev.value==0||iev.value==1)) {
                    process_key(iev.code, iev.value);
                }
            }
        }
    }
    return NULL;
}

/* ── TCP command handler ── */
static void handle_client(int sock) {
    client_sock = sock;
    unsigned char buf[256];
    while (1) {
        int r = read(sock, buf, 1);
        if (r <= 0) break;

        switch (buf[0]) {
            case 0xAA: /* PING */ break;

            case 0x01: /* BTN */ {
                if (read(sock,buf+1,3)<3) goto done;
                int code  = buf[1]|(buf[2]<<8);
                int value = buf[3];
                pthread_mutex_lock(&lock);
                emit_ev(EV_KEY,code,value);
                emit_ev(EV_SYN,SYN_REPORT,0);
                pthread_mutex_unlock(&lock);
                break;
            }
            case 0x02: /* ABS */ {
                if (read(sock,buf+1,6)<6) goto done;
                int code  = buf[1]|(buf[2]<<8);
                int value = (int)(buf[3]|(buf[4]<<8)|(buf[5]<<16)|(buf[6]<<24));
                pthread_mutex_lock(&lock);
                emit_ev(EV_ABS,code,value);
                emit_ev(EV_SYN,SYN_REPORT,0);
                pthread_mutex_unlock(&lock);
                break;
            }
            case 0xFE: /* LOAD MAPPINGS */ {
                if (read(sock,buf+1,1)<1) goto done;
                int nm = buf[1];
                pthread_mutex_lock(&lock);
                n_mappings = 0;
                for (int i=0;i<nm&&i<MAX_MAPPINGS;i++) {
                    unsigned char mb[7];
                    if (read(sock,mb,7)<7) { pthread_mutex_unlock(&lock); goto done; }
                    mappings[i].keycode      = mb[0] | (mb[1]<<8);
                    mappings[i].gamepad_code = mb[2] | (mb[3]<<8);
                    mappings[i].type         = mb[4];
                    mappings[i].axis_value   = mb[5] | (mb[6]<<8);
                    if (mappings[i].axis_value==0) mappings[i].axis_value=32767;
                    n_mappings++;
                }
                fprintf(stderr,"uinput_helper: %d mappings cargados\n",n_mappings);
                pthread_mutex_unlock(&lock);
                break;
            }
            case 0xFF: goto done;
        }
    }
done:
    client_sock = -1;
    close(sock);
    fprintf(stderr,"uinput_helper: cliente desconectado\n");
}

int main(void) {
    fprintf(stderr,"uinput_helper: iniciando daemon root (uid=%d)\n",(int)getuid());
    signal(SIGPIPE,SIG_IGN);

    uinput_fd = setup_gamepad();
    if (uinput_fd < 0) return 1;

    /* Iniciar thread de lectura de teclado */
    pthread_t kbd_tid;
    pthread_create(&kbd_tid,NULL,keyboard_thread,NULL);

    /* Servidor TCP */
    int server = socket(AF_INET,SOCK_STREAM,0);
    int opt=1; setsockopt(server,SOL_SOCKET,SO_REUSEADDR,&opt,sizeof(opt));
    struct sockaddr_in addr={0};
    addr.sin_family=AF_INET;
    addr.sin_addr.s_addr=inet_addr("127.0.0.1");
    addr.sin_port=htons(PORT);
    bind(server,(struct sockaddr*)&addr,sizeof(addr));
    listen(server,1);
    fprintf(stderr,"uinput_helper: escuchando TCP en puerto %d\n",PORT);

    while (1) {
        int client = accept(server,NULL,NULL);
        if (client<0) continue;
        fprintf(stderr,"uinput_helper: app conectada\n");
        handle_client(client);
        fprintf(stderr,"uinput_helper: esperando nueva conexión\n");
    }

    return 0;
}
