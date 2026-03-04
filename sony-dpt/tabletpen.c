/*
 * TabletPen for Sony DPT-RP1 / DPT-S1
 *
 * Turns a rooted Sony Digital Paper into a Bluetooth HID digitizer.
 * Reads Wacom pen input via evdev, sends HID reports over Bluetooth L2CAP.
 *
 * No Android APIs used — raw Linux kernel interfaces only.
 * Statically compiled, zero dependencies beyond libc.
 *
 * Cross-compile:
 *   # Using Android NDK:
 *   $NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi21-clang \
 *     -static -o tabletpen tabletpen.c
 *
 *   # Or using ARM cross-compiler:
 *   arm-linux-gnueabihf-gcc -static -o tabletpen tabletpen.c
 *
 * Deploy:
 *   adb push tabletpen /data/local/tmp/
 *   adb shell chmod +x /data/local/tmp/tabletpen
 *   adb shell /data/local/tmp/tabletpen
 *
 * Or via SSH:
 *   scp tabletpen root@<dpt-ip>:/data/local/tmp/
 *   ssh root@<dpt-ip> /data/local/tmp/tabletpen
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <math.h>
#include <time.h>
#include <dirent.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <linux/input.h>

/* Bluetooth headers — define manually to avoid dependency on libbluetooth */
#define AF_BLUETOOTH 31
#define BTPROTO_L2CAP 0
#define PSM_HID_CTRL 0x11
#define PSM_HID_INTR 0x13

struct sockaddr_l2 {
    sa_family_t l2_family;
    unsigned short l2_psm;
    unsigned char l2_bdaddr[6];
    unsigned short l2_cid;
    unsigned char l2_bdaddr_type;
};

/* HID constants */
#define X_MAX 32767
#define Y_MAX 32767
#define P_MAX 4095

#define REPORT_ID_DIGITIZER 1
#define REPORT_ID_MOUSE 2

/* Default axis ranges for DPT-RP1 */
#define DEFAULT_X_MIN 0
#define DEFAULT_X_MAX 11747
#define DEFAULT_Y_MIN 0
#define DEFAULT_Y_MAX 15891
#define DEFAULT_P_MIN 0
#define DEFAULT_P_MAX 2047

/* Settings */
struct settings {
    int mouse_mode;
    float aspect_w, aspect_h;
    float pressure_floor;
    float pressure_curve;
    float mouse_sensitivity;
    char device_path[256];
};

/* State */
struct pen_state {
    int tip_down;
    int barrel;
    int in_range;
    int x, y, pressure;
    int last_mouse_x, last_mouse_y;
    double barrel_last_time;
};

static volatile int running = 1;

static void sig_handler(int sig) {
    (void)sig;
    running = 0;
}

/* ---- Settings ---- */

static const char *CONFIG_PATH = "/data/local/tmp/.tabletpen.conf";

static void default_settings(struct settings *s) {
    s->mouse_mode = 0;
    s->aspect_w = 16;
    s->aspect_h = 10;
    s->pressure_floor = 0.8f;
    s->pressure_curve = 0.5f;
    s->mouse_sensitivity = 2.0f;
    s->device_path[0] = 0;
}

static void load_settings(struct settings *s) {
    FILE *f = fopen(CONFIG_PATH, "r");
    if (!f) return;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "mouse_mode=%d", &s->mouse_mode) == 1) continue;
        if (sscanf(line, "aspect=%f:%f", &s->aspect_w, &s->aspect_h) == 2) continue;
        if (sscanf(line, "pressure_floor=%f", &s->pressure_floor) == 1) continue;
        if (sscanf(line, "pressure_curve=%f", &s->pressure_curve) == 1) continue;
        if (sscanf(line, "mouse_sensitivity=%f", &s->mouse_sensitivity) == 1) continue;
    }
    fclose(f);
}

static void save_settings(const struct settings *s) {
    FILE *f = fopen(CONFIG_PATH, "w");
    if (!f) return;
    fprintf(f, "mouse_mode=%d\n", s->mouse_mode);
    fprintf(f, "aspect=%.0f:%.0f\n", s->aspect_w, s->aspect_h);
    fprintf(f, "pressure_floor=%.2f\n", s->pressure_floor);
    fprintf(f, "pressure_curve=%.2f\n", s->pressure_curve);
    fprintf(f, "mouse_sensitivity=%.1f\n", s->mouse_sensitivity);
    fclose(f);
}

/* ---- evdev ---- */

static int find_digitizer(char *path, size_t pathlen) {
    char devpath[64];
    char name[256];
    int fd, i;

    for (i = 0; i < 16; i++) {
        snprintf(devpath, sizeof(devpath), "/dev/input/event%d", i);
        fd = open(devpath, O_RDONLY);
        if (fd < 0) continue;

        memset(name, 0, sizeof(name));
        ioctl(fd, EVIOCGNAME(sizeof(name)), name);
        close(fd);

        /* Check for Wacom/pen/digitizer */
        for (char *p = name; *p; p++) *p = (*p >= 'A' && *p <= 'Z') ? *p + 32 : *p;
        if (strstr(name, "wacom") || strstr(name, "pen") || strstr(name, "digitizer")) {
            snprintf(path, pathlen, "%s", devpath);
            printf("Found digitizer: %s (%s)\n", devpath, name);
            return 0;
        }
    }

    /* Fallback: find device with ABS_PRESSURE */
    for (i = 0; i < 16; i++) {
        snprintf(devpath, sizeof(devpath), "/dev/input/event%d", i);
        fd = open(devpath, O_RDONLY);
        if (fd < 0) continue;

        struct input_absinfo absinfo;
        if (ioctl(fd, EVIOCGABS(ABS_PRESSURE), &absinfo) == 0 && absinfo.maximum > 100) {
            memset(name, 0, sizeof(name));
            ioctl(fd, EVIOCGNAME(sizeof(name)), name);
            close(fd);
            snprintf(path, pathlen, "%s", devpath);
            printf("Found pressure device: %s (%s) p_max=%d\n", devpath, name, absinfo.maximum);
            return 0;
        }
        close(fd);
    }

    return -1;
}

static int get_axis_range(const char *devpath, int axis, int *min_out, int *max_out) {
    int fd = open(devpath, O_RDONLY);
    if (fd < 0) return -1;
    struct input_absinfo absinfo;
    int ret = ioctl(fd, EVIOCGABS(axis), &absinfo);
    close(fd);
    if (ret == 0) {
        *min_out = absinfo.minimum;
        *max_out = absinfo.maximum;
    }
    return ret;
}

/* ---- HID reports ---- */

static void build_digitizer_report(unsigned char *buf, int tip, int barrel, int in_range,
                                    int x, int y, int pressure) {
    int buttons = (tip ? 1 : 0) | (barrel ? 2 : 0) | (in_range ? 4 : 0);
    if (x < 0) x = 0; if (x > X_MAX) x = X_MAX;
    if (y < 0) y = 0; if (y > Y_MAX) y = Y_MAX;
    if (pressure < 0) pressure = 0; if (pressure > P_MAX) pressure = P_MAX;

    buf[0] = (unsigned char)buttons;
    buf[1] = x & 0xFF;
    buf[2] = (x >> 8) & 0xFF;
    buf[3] = y & 0xFF;
    buf[4] = (y >> 8) & 0xFF;
    buf[5] = pressure & 0xFF;
    buf[6] = (pressure >> 8) & 0xFF;
}

static void build_mouse_report(unsigned char *buf, int left, int right, int middle,
                                int dx, int dy, int scroll) {
    int buttons = (left ? 1 : 0) | (right ? 2 : 0) | (middle ? 4 : 0);
    if (dx < -127) dx = -127; if (dx > 127) dx = 127;
    if (dy < -127) dy = -127; if (dy > 127) dy = 127;
    if (scroll < -127) scroll = -127; if (scroll > 127) scroll = 127;

    buf[0] = (unsigned char)buttons;
    buf[1] = (unsigned char)(signed char)dx;
    buf[2] = (unsigned char)(signed char)dy;
    buf[3] = (unsigned char)(signed char)scroll;
}

/* ---- Bluetooth ---- */

static void setup_bluetooth(void) {
    printf("Setting up Bluetooth...\n");
    system("svc bluetooth enable 2>/dev/null");
    sleep(2);
    system("hciconfig hci0 up 2>/dev/null");
    system("hciconfig hci0 piscan 2>/dev/null");
    system("hciconfig hci0 name 'TabletPen DPT' 2>/dev/null");
    printf("Bluetooth discoverable\n");
}

static int open_l2cap_server(int psm) {
    int fd = socket(AF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_L2CAP);
    if (fd < 0) return -1;

    int reuse = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    struct sockaddr_l2 addr;
    memset(&addr, 0, sizeof(addr));
    addr.l2_family = AF_BLUETOOTH;
    addr.l2_psm = htobs(psm);

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    if (listen(fd, 1) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* ---- Main loop ---- */

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1000000.0;
}

static int apply_pressure(const struct settings *s, const struct pen_state *st,
                           int raw, int p_min, int p_max) {
    if (!st->tip_down) return 0;
    float normalized = (p_max > p_min) ? (float)(raw - p_min) / (p_max - p_min) : 0;
    if (normalized < 0) normalized = 0;
    float curved = powf(normalized, s->pressure_curve);
    return (int)((s->pressure_floor + curved * (1.0f - s->pressure_floor)) * P_MAX);
}

static void run_loop(const struct settings *s, int evdev_fd, int intr_fd,
                      int x_min, int x_max, int y_min, int y_max,
                      int p_min, int p_max) {
    /* Aspect ratio mapping */
    float target_ratio = s->aspect_w / s->aspect_h;
    int rm_x_range = x_max - x_min;
    int rm_y_range = y_max - y_min;
    float rm_ratio = (rm_y_range > 0) ? (float)rm_x_range / rm_y_range : 1.0f;

    int active_x_range, active_y_range, x_offset, y_offset;
    if (rm_ratio > target_ratio) {
        active_y_range = rm_y_range;
        active_x_range = (int)(rm_y_range * target_ratio);
        x_offset = (rm_x_range - active_x_range) / 2;
        y_offset = 0;
    } else {
        active_x_range = rm_x_range;
        active_y_range = (int)(rm_x_range / target_ratio);
        x_offset = 0;
        y_offset = (rm_y_range - active_y_range) / 2;
    }

    printf("Active area: %dx%d (offset %d,%d)\n", active_x_range, active_y_range, x_offset, y_offset);

    struct pen_state st;
    memset(&st, 0, sizeof(st));
    st.last_mouse_x = -1;
    st.last_mouse_y = -1;

    int mouse_mode = s->mouse_mode;
    struct input_event ev;
    unsigned char packet[9];

    while (running) {
        ssize_t n = read(evdev_fd, &ev, sizeof(ev));
        if (n < (ssize_t)sizeof(ev)) continue;

        if (ev.type == EV_ABS) {
            if (ev.code == ABS_X) st.x = ev.value;
            else if (ev.code == ABS_Y) st.y = ev.value;
            else if (ev.code == ABS_PRESSURE) st.pressure = ev.value;
        } else if (ev.type == EV_KEY) {
            if (ev.code == BTN_TOUCH) {
                st.tip_down = (ev.value == 1);
            } else if (ev.code == BTN_STYLUS || ev.code == BTN_STYLUS2) {
                int new_barrel = (ev.value == 1);
                if (new_barrel && !st.barrel) {
                    double t = now_ms();
                    if (t - st.barrel_last_time < 500) {
                        mouse_mode = !mouse_mode;
                        printf("Mode: %s\n", mouse_mode ? "MOUSE" : "DIGITIZER");
                        st.last_mouse_x = -1;
                        st.last_mouse_y = -1;
                    }
                    st.barrel_last_time = t;
                }
                st.barrel = new_barrel;
            } else if (ev.code == BTN_TOOL_PEN || ev.code == BTN_TOOL_RUBBER) {
                st.in_range = (ev.value == 1);
                if (!st.in_range) {
                    st.last_mouse_x = -1;
                    st.last_mouse_y = -1;
                }
            }
        } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
            if (mouse_mode) {
                /* Mouse mode: relative deltas */
                if (st.last_mouse_x < 0) {
                    st.last_mouse_x = st.x;
                    st.last_mouse_y = st.y;
                    continue;
                }
                int dx = (int)((st.x - st.last_mouse_x) * s->mouse_sensitivity);
                int dy = (int)((st.y - st.last_mouse_y) * s->mouse_sensitivity);
                st.last_mouse_x = st.x;
                st.last_mouse_y = st.y;

                while (dx != 0 || dy != 0) {
                    int sx = dx; if (sx < -127) sx = -127; if (sx > 127) sx = 127;
                    int sy = dy; if (sy < -127) sy = -127; if (sy > 127) sy = 127;

                    packet[0] = 0xA1;
                    packet[1] = REPORT_ID_MOUSE;
                    build_mouse_report(packet + 2, st.tip_down, st.barrel, 0, sx, sy, 0);
                    if (send(intr_fd, packet, 6, 0) < 0) return;
                    dx -= sx;
                    dy -= sy;
                }
            } else {
                /* Digitizer mode: absolute position */
                int ax = st.x - x_min - x_offset;
                int ay = st.y - y_min - y_offset;

                float fx = (active_x_range > 0) ? (float)ax / active_x_range : 0;
                float fy = (active_y_range > 0) ? (float)ay / active_y_range : 0;
                if (fx < 0) fx = 0; if (fx > 1) fx = 1;
                if (fy < 0) fy = 0; if (fy > 1) fy = 1;

                int hx = (int)(fx * X_MAX);
                int hy = (int)(fy * Y_MAX);
                int hp = apply_pressure(s, &st, st.pressure, p_min, p_max);

                packet[0] = 0xA1;
                packet[1] = REPORT_ID_DIGITIZER;
                build_digitizer_report(packet + 2, st.tip_down, st.barrel, st.in_range, hx, hy, hp);
                if (send(intr_fd, packet, 9, 0) < 0) return;
            }
        }
    }
}

/* ---- Main ---- */

static void usage(const char *prog) {
    printf("Usage: %s [options]\n", prog);
    printf("  --mouse          Start in mouse mode\n");
    printf("  --ratio W:H      Aspect ratio (default: 16:10)\n");
    printf("  --floor N        Pressure floor 0-100 (default: 80)\n");
    printf("  --curve F        Pressure curve exponent (default: 0.5)\n");
    printf("  --sensitivity F  Mouse sensitivity (default: 2.0)\n");
    printf("  --device PATH    Input device (auto-detect if omitted)\n");
    printf("  --help           Show this help\n");
}

int main(int argc, char *argv[]) {
    struct settings s;
    default_settings(&s);
    load_settings(&s);

    /* Parse args */
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--mouse") == 0) {
            s.mouse_mode = 1;
        } else if (strcmp(argv[i], "--ratio") == 0 && i + 1 < argc) {
            sscanf(argv[++i], "%f:%f", &s.aspect_w, &s.aspect_h);
        } else if (strcmp(argv[i], "--floor") == 0 && i + 1 < argc) {
            s.pressure_floor = atoi(argv[++i]) / 100.0f;
        } else if (strcmp(argv[i], "--curve") == 0 && i + 1 < argc) {
            s.pressure_curve = atof(argv[++i]);
        } else if (strcmp(argv[i], "--sensitivity") == 0 && i + 1 < argc) {
            s.mouse_sensitivity = atof(argv[++i]);
        } else if (strcmp(argv[i], "--device") == 0 && i + 1 < argc) {
            snprintf(s.device_path, sizeof(s.device_path), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--help") == 0) {
            usage(argv[0]);
            return 0;
        }
    }

    printf("=== TabletPen for Sony DPT-RP1 ===\n");
    printf("Mode: %s | Ratio: %.0f:%.0f | Floor: %.0f%% | Curve: %.2f\n",
           s.mouse_mode ? "Mouse" : "Digitizer",
           s.aspect_w, s.aspect_h, s.pressure_floor * 100, s.pressure_curve);
    printf("Double-press barrel to toggle mode\n\n");

    signal(SIGINT, sig_handler);
    signal(SIGTERM, sig_handler);

    /* Find digitizer */
    if (s.device_path[0] == 0) {
        if (find_digitizer(s.device_path, sizeof(s.device_path)) < 0) {
            fprintf(stderr, "ERROR: Could not find digitizer\n");
            fprintf(stderr, "Try: --device /dev/input/event0\n");
            system("cat /proc/bus/input/devices 2>/dev/null");
            return 1;
        }
    }

    int x_min, x_max, y_min, y_max, p_min, p_max;
    if (get_axis_range(s.device_path, ABS_X, &x_min, &x_max) < 0) { x_min = DEFAULT_X_MIN; x_max = DEFAULT_X_MAX; }
    if (get_axis_range(s.device_path, ABS_Y, &y_min, &y_max) < 0) { y_min = DEFAULT_Y_MIN; y_max = DEFAULT_Y_MAX; }
    if (get_axis_range(s.device_path, ABS_PRESSURE, &p_min, &p_max) < 0) { p_min = DEFAULT_P_MIN; p_max = DEFAULT_P_MAX; }
    printf("Ranges: X=%d-%d Y=%d-%d P=%d-%d\n", x_min, x_max, y_min, y_max, p_min, p_max);

    /* Bluetooth */
    setup_bluetooth();

    int ctrl_srv = open_l2cap_server(PSM_HID_CTRL);
    int intr_srv = open_l2cap_server(PSM_HID_INTR);
    if (ctrl_srv < 0 || intr_srv < 0) {
        fprintf(stderr, "ERROR: Cannot open L2CAP sockets (need root)\n");
        fprintf(stderr, "Try: killall -9 com.sony.dp.hid 2>/dev/null\n");
        return 1;
    }
    printf("HID listening on L2CAP PSM %d/%d\n\n", PSM_HID_CTRL, PSM_HID_INTR);

    save_settings(&s);

    while (running) {
        printf("Waiting for laptop to connect...\n");
        printf("On laptop: Bluetooth → pair with 'TabletPen DPT'\n\n");

        struct sockaddr_l2 peer;
        socklen_t peerlen = sizeof(peer);

        int ctrl_fd = accept(ctrl_srv, (struct sockaddr *)&peer, &peerlen);
        if (ctrl_fd < 0) { if (running) perror("accept ctrl"); continue; }
        printf("Control connected\n");

        int intr_fd = accept(intr_srv, (struct sockaddr *)&peer, &peerlen);
        if (intr_fd < 0) { close(ctrl_fd); if (running) perror("accept intr"); continue; }
        printf("Connected! Reading pen input...\n");

        int evdev_fd = open(s.device_path, O_RDONLY);
        if (evdev_fd < 0) {
            perror("open evdev");
            close(ctrl_fd);
            close(intr_fd);
            continue;
        }

        run_loop(&s, evdev_fd, intr_fd, x_min, x_max, y_min, y_max, p_min, p_max);

        close(evdev_fd);
        close(ctrl_fd);
        close(intr_fd);

        if (running) {
            printf("Connection lost, retrying in 3s...\n");
            sleep(3);
        }
    }

    close(ctrl_srv);
    close(intr_srv);
    printf("Shutdown\n");
    return 0;
}
