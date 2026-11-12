from kotobaya_worker.health import health_status


def test_healthz_ok() -> None:
    assert health_status("/healthz") == (200, b"ok")


def test_unknown_path_404() -> None:
    assert health_status("/nope")[0] == 404
