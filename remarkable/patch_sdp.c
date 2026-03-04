/*
 * Patch the KEYB SDP record to replace its keyboard HID descriptor
 * with our digitizer+mouse descriptor.
 *
 * Uses BlueZ's SDP_SVC_UPDATE_REQ to update the existing record.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stdint.h>

#define SDP_UNIX_PATH "/var/run/sdp"
#define SDP_SVC_UPDATE_REQ 0x77
#define SDP_SVC_UPDATE_RSP 0x78

/* Our HID descriptor */
static const uint8_t hid_desc[] = {
    /* Digitizer Pen (Report ID 1) */
    0x05,0x0D,0x09,0x02,0xA1,0x01,0x85,0x01,
    0x09,0x20,0xA1,0x00,
    0x09,0x42,0x09,0x44,0x09,0x32,
    0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x03,0x81,0x02,
    0x75,0x05,0x95,0x01,0x81,0x03,
    0x05,0x01,0x09,0x30,0x15,0x00,0x26,0xFF,0x7F,
    0x75,0x10,0x95,0x01,0x81,0x02,
    0x09,0x31,0x81,0x02,
    0x05,0x0D,0x09,0x30,0x26,0xFF,0x0F,0x81,0x02,
    0xC0,0xC0,
    /* Mouse (Report ID 2) */
    0x05,0x01,0x09,0x02,0xA1,0x01,0x85,0x02,
    0x09,0x01,0xA1,0x00,
    0x05,0x09,0x19,0x01,0x29,0x03,
    0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x03,0x81,0x02,
    0x75,0x05,0x95,0x01,0x81,0x03,
    0x05,0x01,0x09,0x30,0x09,0x31,
    0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x02,0x81,0x06,
    0x09,0x38,0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x01,0x81,0x06,
    0xC0,0xC0,
};

/*
 * Build an SDP attribute list containing:
 * - 0x0206 (HIDDescriptorList) with our descriptor
 * - 0x0201 (HIDParserVersion)
 * - 0x0202 (HIDDeviceSubclass) = Tablet
 * - 0x0204 (HIDVirtualCable) = true
 * - 0x0205 (HIDReconnectInitiate) = true
 * - 0x000d (AdditionalProtocolDescriptorList) for PSM 19
 */
static int build_update(uint8_t *buf, uint32_t handle) {
    int p = 0;

    /* ServiceRecordHandle (uint32) */
    buf[p++] = (handle >> 24) & 0xFF;
    buf[p++] = (handle >> 16) & 0xFF;
    buf[p++] = (handle >> 8) & 0xFF;
    buf[p++] = handle & 0xFF;

    /* Attribute list as SDP sequence */
    int seq_start = p;
    buf[p++] = 0x36; /* sequence, 2-byte length */
    buf[p++] = 0; buf[p++] = 0; /* placeholder */

    /* Attr 0x000d: AdditionalProtocolDescriptorList */
    buf[p++] = 0x09; buf[p++] = 0x00; buf[p++] = 0x0d; /* uint16 attr id */
    /* { { {L2CAP, PSM 19}, {HIDP} } } */
    buf[p++] = 0x35; buf[p++] = 16; /* outer seq */
    buf[p++] = 0x35; buf[p++] = 14; /* inner seq */
    buf[p++] = 0x35; buf[p++] = 6;  /* L2CAP seq */
    buf[p++] = 0x19; buf[p++] = 0x01; buf[p++] = 0x00; /* uuid L2CAP */
    buf[p++] = 0x09; buf[p++] = 0x00; buf[p++] = 0x13; /* uint16 PSM 19 */
    buf[p++] = 0x35; buf[p++] = 3;  /* HIDP seq */
    buf[p++] = 0x19; buf[p++] = 0x00; buf[p++] = 0x11; /* uuid HIDP */

    /* Attr 0x0201: HIDParserVersion = 0x0111 */
    buf[p++] = 0x09; buf[p++] = 0x02; buf[p++] = 0x01;
    buf[p++] = 0x09; buf[p++] = 0x01; buf[p++] = 0x11;

    /* Attr 0x0202: HIDDeviceSubclass = 0x40 (Tablet) */
    buf[p++] = 0x09; buf[p++] = 0x02; buf[p++] = 0x02;
    buf[p++] = 0x08; buf[p++] = 0x40;

    /* Attr 0x0204: HIDVirtualCable = true */
    buf[p++] = 0x09; buf[p++] = 0x02; buf[p++] = 0x04;
    buf[p++] = 0x28; buf[p++] = 0x01;

    /* Attr 0x0205: HIDReconnectInitiate = true */
    buf[p++] = 0x09; buf[p++] = 0x02; buf[p++] = 0x05;
    buf[p++] = 0x28; buf[p++] = 0x01;

    /* Attr 0x0206: HIDDescriptorList = { { 0x22, <bytes> } } */
    buf[p++] = 0x09; buf[p++] = 0x02; buf[p++] = 0x06;
    int desc_outer = p;
    buf[p++] = 0x35; buf[p++] = 0; /* outer seq */
    int desc_inner = p;
    buf[p++] = 0x35; buf[p++] = 0; /* inner seq */
    buf[p++] = 0x08; buf[p++] = 0x22; /* uint8 report type */
    /* descriptor as text/bytes */
    if (sizeof(hid_desc) < 256) {
        buf[p++] = 0x25; buf[p++] = sizeof(hid_desc);
    } else {
        buf[p++] = 0x26; buf[p++] = (sizeof(hid_desc) >> 8); buf[p++] = sizeof(hid_desc) & 0xFF;
    }
    memcpy(buf + p, hid_desc, sizeof(hid_desc));
    p += sizeof(hid_desc);
    /* Fix inner seq length */
    int inner_len = p - desc_inner - 2;
    buf[desc_inner + 1] = inner_len;
    /* Fix outer seq length */
    int outer_len = p - desc_outer - 2;
    buf[desc_outer + 1] = outer_len;

    /* Attr 0x020e: HIDBootDevice = true */
    buf[p++] = 0x09; buf[p++] = 0x02; buf[p++] = 0x0e;
    buf[p++] = 0x28; buf[p++] = 0x01;

    /* Fix main sequence length */
    int seq_len = p - seq_start - 3;
    buf[seq_start + 1] = (seq_len >> 8) & 0xFF;
    buf[seq_start + 2] = seq_len & 0xFF;

    return p;
}

int main(int argc, char **argv) {
    setbuf(stdout, NULL);

    uint32_t handle = 0x10006; /* Default KEYB handle */
    if (argc > 1) handle = strtoul(argv[1], NULL, 0);

    printf("Patching SDP record 0x%08x with HID descriptor (%zu bytes)...\n",
           handle, sizeof(hid_desc));

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); return 1; }

    struct sockaddr_un addr = {0};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, SDP_UNIX_PATH, sizeof(addr.sun_path) - 1);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("connect SDP");
        return 1;
    }

    uint8_t update[2048];
    int update_len = build_update(update, handle);

    /* SDP_SVC_UPDATE_REQ: [opcode][tid:2][paramlen:2][data] */
    int msglen = 5 + update_len;
    uint8_t *msg = malloc(msglen);
    msg[0] = SDP_SVC_UPDATE_REQ;
    msg[1] = 0; msg[2] = 1;
    msg[3] = (update_len >> 8) & 0xFF;
    msg[4] = update_len & 0xFF;
    memcpy(msg + 5, update, update_len);

    write(fd, msg, msglen);
    printf("Sent %d bytes\n", msglen);

    uint8_t rsp[64];
    int n = read(fd, rsp, sizeof(rsp));
    printf("Response: %d bytes, opcode=0x%02x\n", n, n > 0 ? rsp[0] : 0);
    if (n >= 5) {
        uint16_t status = (rsp[3] << 8) | rsp[4];
        printf("Status: 0x%04x (%s)\n", status, status == 0 ? "OK" : "FAILED");
    }

    free(msg);
    close(fd);
    return 0;
}
