"""Fake Google TV: polo pairing on 6467 and remote protocol on 6466, using androidtvremote2's protobufs."""
import hashlib, os, socket, ssl, sys, threading
from cryptography import x509
from androidtvremote2.polo_pb2 import OuterMessage
from androidtvremote2.remotemessage_pb2 import RemoteMessage
from google.protobuf.internal.decoder import _DecodeVarint
from google.protobuf.internal.encoder import _VarintBytes

CERT, KEY = sys.argv[1], sys.argv[2]


def ib(v):
    return v.to_bytes((v.bit_length() + 7) // 8, "big")


def read_msg(s):
    buf = b""
    while True:
        b = s.recv(1)
        if not b:
            raise EOFError
        buf += b
        if b[0] & 0x80 == 0:
            break
    n, _ = _DecodeVarint(buf, 0)
    data = b""
    while len(data) < n:
        data += s.recv(n - len(data))
    return data


def send(s, m):
    d = m.SerializeToString()
    s.sendall(_VarintBytes(len(d)) + d)


def ctx():
    c = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    c.load_cert_chain(CERT, KEY)
    c.verify_mode = ssl.CERT_OPTIONAL
    c.check_hostname = False
    c.load_verify_locations("/tmp/gtv_client_cert.pem")
    return c


def pairing(conn):
    s = ctx().wrap_socket(conn, server_side=True)
    client = x509.load_der_x509_certificate(s.getpeercert(True)).public_key().public_numbers()
    server = x509.load_pem_x509_certificate(open(CERT, "rb").read()).public_key().public_numbers()
    while True:
        m = OuterMessage(); m.ParseFromString(read_msg(s))
        print("[gtv-pair] got", m.ListFields()[-1][0].name, flush=True)
        r = OuterMessage(); r.protocol_version = 2; r.status = OuterMessage.Status.STATUS_OK
        if m.HasField("pairing_request"):
            print("[gtv-pair] client name:", m.pairing_request.client_name, flush=True)
            r.pairing_request_ack.server_name = "Fake TV"
        elif m.HasField("options"):
            r.options.preferred_role = 1
            e = r.options.input_encodings.add(); e.type = 3; e.symbol_length = 6
        elif m.HasField("configuration"):
            r.configuration_ack.SetInParent()
            nonce = os.urandom(2)
            h = hashlib.sha256(ib(client.n) + ib(client.e) + ib(server.n) + ib(server.e) + nonce).digest()
            code = "%02X" % h[0] + nonce.hex().upper()
            expected = h
            send(s, r)
            open("/tmp/gtv_code.txt", "w").write(code)
            print("[gtv-pair] showing code", code, flush=True)
            continue
        elif m.HasField("secret"):
            ok = m.secret.secret == expected
            print("[gtv-pair] secret ok:", ok, flush=True)
            if not ok:
                r.status = OuterMessage.Status.STATUS_BAD_SECRET
            else:
                r.secret_ack.secret = expected
            send(s, r)
            s.close(); return
        send(s, r)


def remote(conn):
    s = ctx().wrap_socket(conn, server_side=True)
    m = RemoteMessage(); m.remote_configure.code1 = 622
    m.remote_configure.device_info.model = "TV Box S"; m.remote_configure.device_info.vendor = "Xiaomi"
    send(s, m)
    r = RemoteMessage(); r.ParseFromString(read_msg(s)); print("[gtv] configure reply:", r, flush=True)
    m = RemoteMessage(); m.remote_set_active.SetInParent(); send(s, m)
    r = RemoteMessage(); r.ParseFromString(read_msg(s)); print("[gtv] set_active reply:", r, flush=True)
    m = RemoteMessage(); m.remote_ping_request.val1 = 77; send(s, m)
    try:
        while True:
            r = RemoteMessage(); r.ParseFromString(read_msg(s))
            print("[gtv] got:", str(r).replace("\n", " "), flush=True)
    except EOFError:
        print("[gtv] closed", flush=True)


def serve(port, fn):
    ls = socket.socket(); ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ls.bind(("127.0.0.1", port)); ls.listen()
    while True:
        c, _ = ls.accept()
        threading.Thread(target=fn, args=(c,), daemon=True).start()


threading.Thread(target=serve, args=(6467, pairing), daemon=True).start()
serve(6466, remote)
