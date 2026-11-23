"""POSTs a feed payload to ingestion-service's REST intake (9d) and retries what Kafka didn't take."""
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Callable, Optional


@dataclass
class PostResult:
    status: Optional[int]
    attempts: int
    correlation_id: Optional[str] = None
    error: Optional[str] = None

    @property
    def accepted(self) -> bool:
        return self.status == 202


class RestClient:
    """202 = on Kafka; 422 = published but quarantined (a generator bug: every payload it makes is well-formed);
    503 (Kafka down) or a connection error = not stored → wait Retry-After (else 1, 2, 4, 8 s) and retry."""

    def __init__(self, base_url: str, timeout: float = 15.0, max_attempts: int = 5,
                 sleep: Callable[[float], None] = time.sleep):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.max_attempts = max_attempts
        self.sleep = sleep

    def post(self, path: str, payload: str, content_type: str, source_system: str, correlation_id: str) -> PostResult:
        error = None
        for attempt in range(1, self.max_attempts + 1):
            req = urllib.request.Request(self.base_url + path, data=payload.encode("utf-8"), method="POST", headers={
                "Content-Type": content_type, "X-Source-System": source_system, "X-Correlation-Id": correlation_id})
            retry_after = None
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    return PostResult(resp.status, attempt, resp.headers.get("X-Correlation-Id"))
            except urllib.error.HTTPError as e:
                if e.code != 503:
                    return PostResult(e.code, attempt, e.headers.get("X-Correlation-Id"), e.read().decode("utf-8", "replace")[:300])
                error = "HTTP 503"
                retry_after = e.headers.get("Retry-After")
            except (urllib.error.URLError, OSError) as e:
                error = str(e)
            if attempt < self.max_attempts:
                self.sleep(float(retry_after) if retry_after and retry_after.isdigit() else 2 ** (attempt - 1))
        return PostResult(None, self.max_attempts, None, error)
