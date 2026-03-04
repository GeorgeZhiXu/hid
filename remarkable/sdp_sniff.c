/*
 * SDP socket sniffer: sits between sdptool and bluetoothd,
 * captures the raw bytes of SDP_SVC_REGISTER_REQ, saves to file.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <sys/un.h>
#include <signal.h>

#define REAL_SDP "/var/run/sdp"
#define FAKE_SDP "/var/run/sdp_proxy"

static volatile int running = 1;
static void sigint(int s) { (void)s; running = 0; }

int main(void) {
    setbuf(stdout, NULL);
    signal(SIGINT, sigint);

    /* Rename real socket */
    rename(REAL_SDP, REAL_SDP ".real");

    /* Create proxy socket */
    int srv = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un addr = {0};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, REAL_SDP, sizeof(addr.sun_path) - 1);
    unlink(REAL_SDP);
    bind(srv, (struct sockaddr *)&addr, sizeof(addr));
    listen(srv, 5);

    printf("SDP proxy listening. Run 'sdptool add KEYB' now.\n");

    while (running) {
        int client = accept(srv, NULL, NULL);
        if (client < 0) break;

        /* Connect to real SDP server */
        int real_fd = socket(AF_UNIX, SOCK_STREAM, 0);
        struct sockaddr_un real_addr = {0};
        real_addr.sun_family = AF_UNIX;
        strncpy(real_addr.sun_path, REAL_SDP ".real", sizeof(real_addr.sun_path) - 1);
        if (connect(real_fd, (struct sockaddr *)&real_addr, sizeof(real_addr)) < 0) {
            perror("connect real");
            close(client);
            close(real_fd);
            continue;
        }

        /* Forward data bidirectionally, logging client->server */
        uint8_t buf[8192];
        while (1) {
            fd_set fds;
            FD_ZERO(&fds);
            FD_SET(client, &fds);
            FD_SET(real_fd, &fds);
            int maxfd = (client > real_fd ? client : real_fd) + 1;

            if (select(maxfd, &fds, NULL, NULL, NULL) <= 0) break;

            if (FD_ISSET(client, &fds)) {
                int n = read(client, buf, sizeof(buf));
                if (n <= 0) break;
                /* Log client->server (this is the SDP register request) */
                printf("Client->Server: %d bytes, opcode=0x%02x\n", n, buf[0]);
                FILE *f = fopen("/tmp/sdp_register.bin", "wb");
                if (f) { fwrite(buf, 1, n, f); fclose(f); }
                /* Hex dump */
                for (int i = 0; i < n && i < 512; i++) {
                    printf("%02x ", buf[i]);
                    if ((i + 1) % 32 == 0) printf("\n");
                }
                printf("\n");
                write(real_fd, buf, n);
            }

            if (FD_ISSET(real_fd, &fds)) {
                int n = read(real_fd, buf, sizeof(buf));
                if (n <= 0) break;
                printf("Server->Client: %d bytes, opcode=0x%02x\n", n, buf[0]);
                write(client, buf, n);
            }
        }

        close(client);
        close(real_fd);
    }

    /* Restore real socket */
    unlink(REAL_SDP);
    rename(REAL_SDP ".real", REAL_SDP);
    close(srv);
    printf("Cleaned up.\n");
    return 0;
}
