/*
 * Minimal BlueZ pairing agent — auto-accepts all pairing requests.
 * Uses dbus-send for simplicity (no libdbus linking needed).
 * Registers via bluetoothctl in a background process.
 */
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>

static volatile int running = 1;
static void sigint(int s) { (void)s; running = 0; }

int main(void) {
    setbuf(stdout, NULL);
    signal(SIGINT, sigint);

    printf("Registering pairing agent...\n");

    /* Use a pipe to feed commands to bluetoothctl */
    FILE *bt = popen("bluetoothctl 2>&1", "w");
    if (!bt) { perror("popen"); return 1; }

    fprintf(bt, "agent NoInputNoOutput\n");
    fflush(bt);
    sleep(1);

    fprintf(bt, "default-agent\n");
    fflush(bt);
    sleep(1);

    printf("Agent registered. Waiting for pairing requests...\n");

    /* Keep alive — the agent lives as long as bluetoothctl is running */
    while (running) {
        sleep(1);
        /* Send a keepalive to prevent timeout */
        fprintf(bt, "\n");
        fflush(bt);
    }

    fprintf(bt, "quit\n");
    pclose(bt);
    printf("Agent stopped.\n");
    return 0;
}
