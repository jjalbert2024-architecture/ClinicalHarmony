"""Minimal MLLP (Minimal Lower Layer Protocol) client: <VT> message <FS><CR>, one ACK per message.

A real interface engine's sender semantics: a message is only "sent" once the receiver returns
MSA-1 = AA. On AE / AR, a timeout or a dropped connection it reconnects and retries with backoff.
"""
import socket
import time
from dataclasses import dataclass
from typing import Callable, Optional

START_BLOCK = b"\x0b"
END_BLOCK = b"\x1c\x0d"


@dataclass
class SendResult:
    ack_code: Optional[str]   # AA / AE / AR, or None if no ACK ever arrived
    attempts: int
    ack: Optional[str]
    error: Optional[str] = None

    @property
    def accepted(self) -> bool:
        return self.ack_code == "AA"


def frame(message: str) -> bytes:
    return START_BLOCK + message.encode("utf-8") + END_BLOCK


def ack_code(ack: str) -> Optional[str]:
    for segment in ack.replace("\n", "\r").split("\r"):
        if segment.startswith("MSA|"):
            fields = segment.split("|")
            return fields[1] if len(fields) > 1 else None
    return None


class MllpClient:
    def __init__(self, host: str, port: int, timeout: float = 15.0, max_attempts: int = 5,
                 backoff: float = 1.0, sleep: Callable[[float], None] = time.sleep):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.max_attempts = max_attempts
        self.backoff = backoff
        self.sleep = sleep
        self._sock: Optional[socket.socket] = None
        self._buffer = b""

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def close(self):
        if self._sock is not None:
            try:
                self._sock.close()
            finally:
                self._sock = None
                self._buffer = b""

    def _connect(self):
        if self._sock is None:
            self._sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
            self._sock.settimeout(self.timeout)

    def _read_ack(self) -> str:
        while END_BLOCK not in self._buffer:
            chunk = self._sock.recv(4096)
            if not chunk:
                raise ConnectionError("connection closed before ACK")
            self._buffer += chunk
        frame_bytes, self._buffer = self._buffer.split(END_BLOCK, 1)
        start = frame_bytes.find(START_BLOCK)
        return frame_bytes[start + 1:].decode("utf-8", errors="replace")

    def send(self, message: str) -> SendResult:
        last_error, last_ack, code = None, None, None
        for attempt in range(1, self.max_attempts + 1):
            try:
                self._connect()
                self._sock.sendall(frame(message))
                last_ack = self._read_ack()
                code = ack_code(last_ack)
                if code == "AA":
                    return SendResult(code, attempt, last_ack)
                last_error = "ACK %s" % code
            except (OSError, ConnectionError) as e:
                last_error = "%s: %s" % (type(e).__name__, e)
                self.close()
            if attempt < self.max_attempts:
                self.sleep(self.backoff * (2 ** (attempt - 1)))
        return SendResult(code, self.max_attempts, last_ack, last_error)
