import importlib.util
import struct
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("runtime", Path(__file__).with_name("server.py"))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeTest(unittest.TestCase):
    def test_cannot_read_logs_outside_project_or_inject_path(self):
        with patch.object(runtime, "containers", return_value=[]), patch.object(runtime, "docker_get") as docker:
            with self.assertRaises(LookupError):
                runtime.logs("a" * 64, "1h", 100)
            with self.assertRaises(ValueError):
                runtime.logs("../other", "1h", 100)
            with self.assertRaises(ValueError):
                runtime.logs("a" * 64, "1h", 501)
            with self.assertRaises(ValueError):
                runtime.logs("a" * 64, "7d", 100)
            docker.assert_not_called()

    def test_decodes_both_streams_and_redacts_credentials(self):
        chunks = [b"access_token=secret\n", b"Authorization: Bearer abc.def.ghi\n"]
        data = b"".join(bytes([number + 1, 0, 0, 0]) + struct.pack(">I", len(chunk)) + chunk
                        for number, chunk in enumerate(chunks))
        text = runtime.scrub(runtime.decode_logs(data, False))
        self.assertNotIn("=secret", text)
        self.assertNotIn("abc.def.ghi", text)
        self.assertIn("[REDACTED]", text)

    def test_project_label_is_checked_even_if_docker_ignores_filter(self):
        with patch.object(runtime, "docker_get", return_value=[
            {"Id": "a" * 64, "Labels": {"com.docker.compose.project": runtime.PROJECT}},
            {"Id": "b" * 64, "Labels": {"com.docker.compose.project": "other"}},
        ]):
            self.assertEqual([row["Id"] for row in runtime.containers()], ["a" * 64])


if __name__ == "__main__":
    unittest.main()
