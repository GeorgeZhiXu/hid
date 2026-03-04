/*
 * Register a proper HID SDP service record with BlueZ's SDP server.
 * Must run while bluetoothd --compat is active (provides /var/run/sdp).
 *
 * This does what "sdptool add KEYB" does, but includes the full HID
 * descriptor so macOS can discover and connect properly.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stdint.h>

/* SDP socket path (BlueZ compat mode) */
#define SDP_UNIX_PATH "/var/run/sdp"

/* HID descriptor (digitizer + mouse) */
static const uint8_t hid_descriptor[] = {
    /* Digitizer Pen (Report ID 1) */
    0x05, 0x0D, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x01,
    0x09, 0x20, 0xA1, 0x00,
    0x09, 0x42, 0x09, 0x44, 0x09, 0x32,
    0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03, 0x81, 0x02,
    0x75, 0x05, 0x95, 0x01, 0x81, 0x03,
    0x05, 0x01, 0x09, 0x30, 0x15, 0x00, 0x26, 0xFF, 0x7F,
    0x75, 0x10, 0x95, 0x01, 0x81, 0x02,
    0x09, 0x31, 0x81, 0x02,
    0x05, 0x0D, 0x09, 0x30, 0x26, 0xFF, 0x0F, 0x81, 0x02,
    0xC0, 0xC0,
    /* Mouse (Report ID 2) */
    0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02,
    0x09, 0x01, 0xA1, 0x00,
    0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
    0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03, 0x81, 0x02,
    0x75, 0x05, 0x95, 0x01, 0x81, 0x03,
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
    0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x02, 0x81, 0x06,
    0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
    0xC0, 0xC0,
};

/*
 * Build a raw SDP record as a byte buffer.
 * This is tedious but avoids linking against libbluetooth-dev.
 */

/* SDP Data Element helpers */
static int put_de_uint8(uint8_t *buf, uint8_t v) {
    buf[0] = 0x08; /* uint8 */
    buf[1] = v;
    return 2;
}

static int put_de_uint16(uint8_t *buf, uint16_t v) {
    buf[0] = 0x09; /* uint16 */
    buf[1] = (v >> 8) & 0xFF;
    buf[2] = v & 0xFF;
    return 3;
}

static int put_de_uint32(uint8_t *buf, uint32_t v) {
    buf[0] = 0x0A; /* uint32 */
    buf[1] = (v >> 24) & 0xFF;
    buf[2] = (v >> 16) & 0xFF;
    buf[3] = (v >> 8) & 0xFF;
    buf[4] = v & 0xFF;
    return 5;
}

static int put_de_bool(uint8_t *buf, int v) {
    buf[0] = 0x28; /* bool */
    buf[1] = v ? 1 : 0;
    return 2;
}

static int put_de_uuid16(uint8_t *buf, uint16_t v) {
    buf[0] = 0x19; /* uuid16 */
    buf[1] = (v >> 8) & 0xFF;
    buf[2] = v & 0xFF;
    return 3;
}

static int put_de_text(uint8_t *buf, const char *s) {
    int len = strlen(s);
    if (len < 256) {
        buf[0] = 0x25; /* text, 1-byte length */
        buf[1] = len;
        memcpy(buf + 2, s, len);
        return 2 + len;
    }
    return 0; /* not handling longer strings */
}

static int put_de_bytes(uint8_t *buf, const uint8_t *data, int len) {
    if (len < 256) {
        buf[0] = 0x25; /* text/bytes, 1-byte length */
        buf[1] = len;
        memcpy(buf + 2, data, len);
        return 2 + len;
    }
    buf[0] = 0x26; /* text/bytes, 2-byte length */
    buf[1] = (len >> 8) & 0xFF;
    buf[2] = len & 0xFF;
    memcpy(buf + 3, data, len);
    return 3 + len;
}

/* Start a sequence, returns header size. Caller must fix length after. */
static int put_seq_start(uint8_t *buf) {
    buf[0] = 0x36; /* sequence, 2-byte length */
    buf[1] = 0;
    buf[2] = 0;
    return 3;
}

static void fix_seq_len(uint8_t *seq_start, int body_len) {
    seq_start[1] = (body_len >> 8) & 0xFF;
    seq_start[2] = body_len & 0xFF;
}

/* Attribute: uint16 ID + value */
static int put_attr_uint16(uint8_t *buf, uint16_t id, uint16_t val) {
    int p = put_de_uint16(buf, id);
    p += put_de_uint16(buf + p, val);
    return p;
}

static int put_attr_uint8(uint8_t *buf, uint16_t id, uint8_t val) {
    int p = put_de_uint16(buf, id);
    p += put_de_uint8(buf + p, val);
    return p;
}

static int put_attr_bool(uint8_t *buf, uint16_t id, int val) {
    int p = put_de_uint16(buf, id);
    p += put_de_bool(buf + p, val);
    return p;
}

static int put_attr_text(uint8_t *buf, uint16_t id, const char *val) {
    int p = put_de_uint16(buf, id);
    p += put_de_text(buf + p, val);
    return p;
}

static int build_hid_sdp_record(uint8_t *buf, int bufsize) {
    int p = 0;

    /* Outer sequence */
    int outer = p;
    p += put_seq_start(buf + p);

    /* 0x0001: ServiceClassIDList = { HID(0x1124) } */
    p += put_de_uint16(buf + p, 0x0001);
    int s1 = p; p += put_seq_start(buf + p);
    p += put_de_uuid16(buf + p, 0x1124);
    fix_seq_len(buf + s1, p - s1 - 3);

    /* 0x0004: ProtocolDescriptorList = { {L2CAP, PSM=17}, {HIDP} } */
    p += put_de_uint16(buf + p, 0x0004);
    int s2 = p; p += put_seq_start(buf + p);
    int s2a = p; p += put_seq_start(buf + p);
    p += put_de_uuid16(buf + p, 0x0100); /* L2CAP */
    p += put_de_uint16(buf + p, 0x0011); /* PSM 17 */
    fix_seq_len(buf + s2a, p - s2a - 3);
    int s2b = p; p += put_seq_start(buf + p);
    p += put_de_uuid16(buf + p, 0x0011); /* HIDP */
    fix_seq_len(buf + s2b, p - s2b - 3);
    fix_seq_len(buf + s2, p - s2 - 3);

    /* 0x0005: BrowseGroupList = { PublicBrowseRoot } */
    p += put_de_uint16(buf + p, 0x0005);
    int s3 = p; p += put_seq_start(buf + p);
    p += put_de_uuid16(buf + p, 0x1002);
    fix_seq_len(buf + s3, p - s3 - 3);

    /* 0x0006: LanguageBaseAttributeIDList */
    p += put_de_uint16(buf + p, 0x0006);
    int s4 = p; p += put_seq_start(buf + p);
    p += put_de_uint16(buf + p, 0x656e); /* en */
    p += put_de_uint16(buf + p, 0x006a); /* UTF-8 */
    p += put_de_uint16(buf + p, 0x0100); /* base */
    fix_seq_len(buf + s4, p - s4 - 3);

    /* 0x0009: ProfileDescriptorList = { {HID, v1.0} } */
    p += put_de_uint16(buf + p, 0x0009);
    int s5 = p; p += put_seq_start(buf + p);
    int s5a = p; p += put_seq_start(buf + p);
    p += put_de_uuid16(buf + p, 0x1124);
    p += put_de_uint16(buf + p, 0x0100);
    fix_seq_len(buf + s5a, p - s5a - 3);
    fix_seq_len(buf + s5, p - s5 - 3);

    /* 0x000d: AdditionalProtocolDescriptorList (interrupt channel, PSM 19) */
    p += put_de_uint16(buf + p, 0x000d);
    int s6 = p; p += put_seq_start(buf + p);
    int s6a = p; p += put_seq_start(buf + p);
    int s6b = p; p += put_seq_start(buf + p);
    p += put_de_uuid16(buf + p, 0x0100); /* L2CAP */
    p += put_de_uint16(buf + p, 0x0013); /* PSM 19 */
    fix_seq_len(buf + s6b, p - s6b - 3);
    int s6c = p; p += put_seq_start(buf + p);
    p += put_de_uuid16(buf + p, 0x0011); /* HIDP */
    fix_seq_len(buf + s6c, p - s6c - 3);
    fix_seq_len(buf + s6a, p - s6a - 3);
    fix_seq_len(buf + s6, p - s6 - 3);

    /* 0x0100: ServiceName */
    p += put_attr_text(buf + p, 0x0100, "TabletPen");
    /* 0x0101: ServiceDescription */
    p += put_attr_text(buf + p, 0x0101, "HID Digitizer Tablet");
    /* 0x0102: ProviderName */
    p += put_attr_text(buf + p, 0x0102, "TabletPen");

    /* HID attributes */
    p += put_attr_uint16(buf + p, 0x0200, 0x0100); /* HIDDeviceReleaseNumber */
    p += put_attr_uint16(buf + p, 0x0201, 0x0111); /* HIDParserVersion */
    p += put_attr_uint8(buf + p, 0x0202, 0x40);    /* HIDDeviceSubclass: Tablet */
    p += put_attr_uint8(buf + p, 0x0203, 0x00);    /* HIDCountryCode */
    p += put_attr_bool(buf + p, 0x0204, 1);        /* HIDVirtualCable */
    p += put_attr_bool(buf + p, 0x0205, 1);        /* HIDReconnectInitiate */

    /* 0x0206: HIDDescriptorList = { { 0x22, <descriptor bytes> } } */
    p += put_de_uint16(buf + p, 0x0206);
    int s7 = p; p += put_seq_start(buf + p);
    int s7a = p; p += put_seq_start(buf + p);
    p += put_de_uint8(buf + p, 0x22); /* Report descriptor type */
    p += put_de_bytes(buf + p, hid_descriptor, sizeof(hid_descriptor));
    fix_seq_len(buf + s7a, p - s7a - 3);
    fix_seq_len(buf + s7, p - s7 - 3);

    /* 0x0207: HIDLANGIDBaseList */
    p += put_de_uint16(buf + p, 0x0207);
    int s8 = p; p += put_seq_start(buf + p);
    int s8a = p; p += put_seq_start(buf + p);
    p += put_de_uint16(buf + p, 0x0409); /* English US */
    p += put_de_uint16(buf + p, 0x0100);
    fix_seq_len(buf + s8a, p - s8a - 3);
    fix_seq_len(buf + s8, p - s8 - 3);

    p += put_attr_uint16(buf + p, 0x020b, 0x0100); /* HIDProfileVersion */
    p += put_attr_uint16(buf + p, 0x020c, 0x0c80); /* HIDSupervisionTimeout */
    p += put_attr_bool(buf + p, 0x020d, 0);        /* HIDNormallyConnectable */
    p += put_attr_bool(buf + p, 0x020e, 1);        /* HIDBootDevice */

    /* Fix outer sequence length */
    fix_seq_len(buf + outer, p - outer - 3);

    return p;
}

/*
 * Register the SDP record via BlueZ's Unix socket (/var/run/sdp).
 * Protocol: SDP_SVC_REGISTER_REQ (opcode 0x75)
 */
#define SDP_SVC_REGISTER_REQ 0x75
#define SDP_SVC_REGISTER_RSP 0x76

int main(void) {
    setbuf(stdout, NULL);

    /* Build SDP record */
    uint8_t record[4096];
    int reclen = build_hid_sdp_record(record, sizeof(record));
    printf("SDP record: %d bytes\n", reclen);

    /* Connect to BlueZ SDP server */
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); return 1; }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, SDP_UNIX_PATH, sizeof(addr.sun_path) - 1);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("connect to SDP server");
        printf("Make sure bluetoothd is running with --compat\n");
        close(fd);
        return 1;
    }
    printf("Connected to SDP server\n");

    /* Send SDP_SVC_REGISTER_REQ
     * Format: [opcode:1] [tid:2] [paramlen:2] [record_data...] */
    int msglen = 5 + reclen;
    uint8_t *msg = malloc(msglen);
    msg[0] = SDP_SVC_REGISTER_REQ;
    msg[1] = 0; msg[2] = 1; /* tid = 1 */
    msg[3] = (reclen >> 8) & 0xFF;
    msg[4] = reclen & 0xFF;
    memcpy(msg + 5, record, reclen);

    int sent = write(fd, msg, msglen);
    if (sent != msglen) {
        perror("write");
        free(msg);
        close(fd);
        return 1;
    }
    printf("Sent %d bytes to SDP server\n", sent);

    /* Read response */
    uint8_t rsp[256];
    int rsplen = read(fd, rsp, sizeof(rsp));
    if (rsplen >= 5 && rsp[0] == SDP_SVC_REGISTER_RSP) {
        uint32_t handle = (rsp[5] << 24) | (rsp[6] << 16) | (rsp[7] << 8) | rsp[8];
        printf("SDP record registered, handle=0x%08x\n", handle);
    } else {
        printf("SDP registration response: %d bytes, opcode=0x%02x\n",
               rsplen, rsplen > 0 ? rsp[0] : 0);
        if (rsplen > 0) {
            printf("Raw: ");
            for (int i = 0; i < rsplen && i < 20; i++) printf("%02x ", rsp[i]);
            printf("\n");
        }
    }

    /* Keep connection open — SDP record is removed when we disconnect */
    printf("SDP record active. Press Ctrl+C to remove.\n");
    while (1) sleep(3600);

    free(msg);
    close(fd);
    return 0;
}
