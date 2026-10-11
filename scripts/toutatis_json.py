"""Runs Toutatis' lookups and prints JSON instead of text.

The Instagram sessionid is read from the IG_SESSIONID environment variable so it never
appears on the command line (visible in process lists).

Usage: IG_SESSIONID=... python toutatis_json.py <username>
"""
import json
import os
import sys

from toutatis.core import advanced_lookup, getInfo

KEEP = [
    "username", "userID", "full_name", "is_verified", "is_business", "is_private",
    "follower_count", "following_count", "media_count", "external_url", "biography",
    "category", "public_email", "public_phone_country_code", "public_phone_number",
    "city_name", "address_street", "zip", "is_whatsapp_linked", "is_memorialized",
    "is_new_to_instagram", "total_igtv_videos",
]


def main():
    args = [a for a in sys.argv[1:] if a != "--"]
    username = args[0]
    session = os.environ.get("IG_SESSIONID", "")
    info = getInfo(username, session)
    if not info.get("user"):
        print(json.dumps({"error": info.get("error") or "lookup failed"}))
        return

    user = info["user"]
    out = {k: user.get(k) for k in KEEP if user.get(k) not in (None, "", [])}
    pic = (user.get("hd_profile_pic_url_info") or {}).get("url")
    if pic:
        out["profilePicture"] = pic
    out["profileUrl"] = f"https://www.instagram.com/{username}/"

    adv = advanced_lookup(username)
    if adv.get("error"):
        out["recoveryLookup"] = adv["error"]
    else:
        u = adv.get("user") or {}
        out["obfuscatedEmail"] = u.get("obfuscated_email") or None
        out["obfuscatedPhone"] = u.get("obfuscated_phone") or None
        if u.get("message"):
            out["recoveryLookup"] = u["message"]

    print(json.dumps(out, default=str))


if __name__ == "__main__":
    main()
