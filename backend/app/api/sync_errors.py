"""Errors a /sync op handler raises; app/api/sync.py turns them into per-op conflicts."""

class StaleUpdateError(Exception):
    """Le client tente de modifier une row plus recente cote serveur."""
    def __init__(self, server_data: dict | None = None):
        self.server_data = server_data


class OutOfScopeError(Exception):
    """The op can never apply: its household is not one of the caller's, or its row is gone.

    Reported as `out_of_scope`, which the client drops from its queue. Any other
    conflict stays queued and is replayed on every pass.
    """
