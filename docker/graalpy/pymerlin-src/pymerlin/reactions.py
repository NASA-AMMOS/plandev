from pymerlin.model_actions import wait_until


def monitor_updates(query):
    """
    Pause until the value returned from the query changes
    """
    latest_value = query()
    while True:
        wait_until(lambda: query() != latest_value)
        latest_value = query()
        yield latest_value