"""Fake Apple TV Companion server built from pyatv's own server-side auth code."""
import asyncio, sys
from pyatv.protocols.companion.server_auth import CompanionServerAuth
from pyatv.protocols.companion.connection import FrameType
from pyatv.auth.server_auth import PIN_CODE
from pyatv.support import opack, chacha20

PORT = int(sys.argv[1])
open("/tmp/atv_pin.txt", "w").write(str(PIN_CODE))
hid = []


class Server(asyncio.Protocol, CompanionServerAuth):
    def __init__(self):
        CompanionServerAuth.__init__(self, "Fake ATV")
        self.buf = b""
        self.chacha = None

    def connection_made(self, t):
        self.t = t
        print("[atv] client connected", flush=True)

    def send_to_client(self, frame_type, data):
        self._send(frame_type, opack.pack(data))

    def _send(self, frame_type, payload):
        n = len(payload) + (16 if self.chacha else 0)
        header = bytes([frame_type.value]) + n.to_bytes(3, "big")
        if self.chacha:
            payload = self.chacha.encrypt(payload, aad=header)
        self.t.write(header + payload)

    def enable_encryption(self, out_key, in_key):
        self.chacha = chacha20.Chacha20Cipher(out_key, in_key, nonce_length=12)
        print("[atv] encryption on", flush=True)

    def has_paired(self):
        print("[atv] PAIRED", flush=True)

    def data_received(self, data):
        self.buf += data
        while len(self.buf) >= 4:
            n = int.from_bytes(self.buf[1:4], "big")
            if len(self.buf) < 4 + n:
                return
            header, payload = self.buf[:4], self.buf[4:4 + n]
            self.buf = self.buf[4 + n:]
            ft = FrameType(header[0])
            if self.chacha and n:
                payload = self.chacha.decrypt(payload, aad=header)
            msg, _ = opack.unpack(payload)
            if ft in (FrameType.PS_Start, FrameType.PS_Next, FrameType.PV_Start, FrameType.PV_Next):
                self.handle_auth_frame(ft, msg)
            else:
                print("[atv] request", msg["_i"], msg.get("_c"), flush=True)
                if msg["_i"] == "_hidC":
                    hid.append((msg["_c"]["_hidC"], msg["_c"]["_hBtS"]))
                content = {"_sid": 1234} if msg["_i"] == "_sessionStart" else {}
                self._send(FrameType.E_OPACK, opack.pack({"_c": content, "_t": 3, "_x": msg["_x"]}))

    def connection_lost(self, exc):
        print("[atv] closed; hid =", hid, flush=True)


async def main():
    loop = asyncio.get_running_loop()
    srv = await loop.create_server(Server, "127.0.0.1", PORT)
    async with srv:
        await srv.serve_forever()

asyncio.run(main())
