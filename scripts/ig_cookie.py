"""Read the current instagram.com 'sessionid' cookie from the user's own browser.

Scope: reads ONLY the 'sessionid' cookie for instagram.com, from the browsers of the
user running this tool, on this machine. Nothing else is read or stored. Used by the
"Load from browser" button so the user doesn't have to paste the cookie by hand.

Prints one JSON line: {"sessionid": "...", "source": "Firefox"} or {"error": "..."}.
"""
import json


def main():
    try:
        import browser_cookie3 as bc3
    except ImportError:
        print(json.dumps({"error": "browser_cookie3 is not installed, run setup-python-tools.cmd"}))
        return

    # Firefox first: its cookie store is not encrypted, so it is the most reliable.
    loaders = [
        ("Firefox", getattr(bc3, "firefox", None)),
        ("LibreWolf", getattr(bc3, "librewolf", None)),
        ("Chrome", getattr(bc3, "chrome", None)),
        ("Edge", getattr(bc3, "edge", None)),
        ("Brave", getattr(bc3, "brave", None)),
        ("Chromium", getattr(bc3, "chromium", None)),
        ("Opera", getattr(bc3, "opera", None)),
        ("Vivaldi", getattr(bc3, "vivaldi", None)),
    ]

    errors = []
    for name, loader in loaders:
        if loader is None:
            continue
        try:
            jar = loader(domain_name="instagram.com")
            for cookie in jar:
                if cookie.name == "sessionid" and cookie.value:
                    print(json.dumps({"sessionid": cookie.value, "source": name}))
                    return
        except Exception as e:
            # Locked DB, app-bound encryption (recent Chrome/Edge), or browser not installed
            errors.append(f"{name}: {type(e).__name__}")

    detail = ("; ".join(errors)) if errors else "no supported browser found"
    print(json.dumps({"error": "No Instagram sessionid found. Log in to instagram.com in Firefox "
                               "(most reliable) or Chrome/Edge, then try again. Recent Chrome/Edge "
                               "encrypt cookies and may block reading. [" + detail + "]"}))


if __name__ == "__main__":
    main()
