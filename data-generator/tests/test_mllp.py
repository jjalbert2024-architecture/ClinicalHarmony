import socketserver
import threading
import unittest

from hl7gen.mllp import MllpClient, ack_code, frame, END_BLOCK, START_BLOCK


class _Handler(socketserver.BaseRequestHandler):
    def handle(self):
        buf = b""
        while True:
            chunk = self.request.recv(4096)
            if not chunk:
                return
            buf += chunk
            while END_BLOCK in buf:
                msg, buf = buf.split(END_BLOCK, 1)
                self.server.received.append(msg[msg.find(START_BLOCK) + 1:].decode())
                code = self.server.codes.pop(0) if self.server.codes else "AA"
                if code == "DROP":
                    return  # close without ACK
                ack = "MSH|^~\\&|CH|ING|||20261114||ACK|A1|P|2.5\rMSA|%s|C1\r" % code
                self.request.sendall(START_BLOCK + ack.encode() + END_BLOCK)


class FakeMllpServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, codes):
        super().__init__(("127.0.0.1", 0), _Handler)
        self.codes = list(codes)
        self.received = []


class MllpClientTest(unittest.TestCase):

    def run_server(self, codes):
        server = FakeMllpServer(codes)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        self.addCleanup(server.server_close)
        return server

    def test_framing(self):
        self.assertEqual(frame("MSH|x\r"), b"\x0bMSH|x\r\x1c\x0d")
        self.assertEqual(ack_code("MSH|...\rMSA|AE|C1|text\r"), "AE")
        self.assertIsNone(ack_code("garbage"))

    def test_sends_many_messages_on_one_connection(self):
        server = self.run_server([])
        with MllpClient("127.0.0.1", server.server_address[1], sleep=lambda s: None) as c:
            for i in range(3):
                r = c.send("MSH|^~\\&|A|F|||1||ADT^A01|C%d|P|2.5\r" % i)
                self.assertTrue(r.accepted)
                self.assertEqual(r.attempts, 1)
        self.assertEqual(len(server.received), 3)
        self.assertTrue(server.received[0].endswith("\r"))

    def test_retries_after_ae_until_aa(self):
        server = self.run_server(["AE", "AE", "AA"])
        sleeps = []
        with MllpClient("127.0.0.1", server.server_address[1], backoff=0.5, sleep=sleeps.append) as c:
            r = c.send("MSH|x\r")
        self.assertTrue(r.accepted)
        self.assertEqual(r.attempts, 3)
        self.assertEqual(sleeps, [0.5, 1.0])

    def test_reconnects_after_dropped_connection(self):
        server = self.run_server(["DROP", "AA"])
        with MllpClient("127.0.0.1", server.server_address[1], sleep=lambda s: None) as c:
            r = c.send("MSH|x\r")
        self.assertTrue(r.accepted)
        self.assertEqual(r.attempts, 2)

    def test_gives_up_after_max_attempts(self):
        server = self.run_server(["AE"] * 10)
        with MllpClient("127.0.0.1", server.server_address[1], max_attempts=3, sleep=lambda s: None) as c:
            r = c.send("MSH|x\r")
        self.assertFalse(r.accepted)
        self.assertEqual(r.ack_code, "AE")
        self.assertEqual(r.attempts, 3)

    def test_unreachable_port_fails_cleanly(self):
        with MllpClient("127.0.0.1", 1, max_attempts=2, timeout=1, sleep=lambda s: None) as c:
            r = c.send("MSH|x\r")
        self.assertFalse(r.accepted)
        self.assertIn("Error", r.error)


if __name__ == "__main__":
    unittest.main()
