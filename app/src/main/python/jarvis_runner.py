"""Runs one piece of learner code and returns what it printed.

The code is stopped after LIMIT_SECONDS, so an endless loop cannot hang the app.
Reading from the keyboard (input()) is not available here.
"""
import contextlib
import io
import sys
import time
import traceback

LIMIT_SECONDS = 5.0


def run(code):
    out = io.StringIO()
    started = time.time()

    def tracer(frame, event, arg):
        if time.time() - started > LIMIT_SECONDS:
            raise TimeoutError("5 second ki limit khatam. Code shayad kisi loop mein atak gaya.")
        return tracer

    def no_input(*args, **kwargs):
        raise EOFError("input() abhi is app mein nahi chalta. Value code mein hi likhiye.")

    namespace = {"__name__": "__main__", "input": no_input}
    previous = sys.gettrace()
    try:
        sys.settrace(tracer)
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            exec(compile(code, "<lesson>", "exec"), namespace)
    except BaseException as error:
        lines = traceback.format_exception(type(error), error, error.__traceback__)
        # Only the last lines matter to the learner: the error and where it happened.
        out.write("".join(lines[-4:]))
    finally:
        sys.settrace(previous)

    text = out.getvalue().rstrip()
    return text if text else "(koi output nahi aaya)"
