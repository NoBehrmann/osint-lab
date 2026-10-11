"""Offline phone number analysis with libphonenumber. Prints JSON.

Usage: python phone.py <number> <default-region>
"""
import json
import sys

import phonenumbers
from phonenumbers import carrier, geocoder, timezone, PhoneNumberType

TYPES = {v: k for k, v in vars(PhoneNumberType).items() if k.isupper()}


def main():
    number, region = [a for a in sys.argv[1:] if a != "--"][:2]
    try:
        n = phonenumbers.parse(number, None if number.startswith("+") else region)
    except phonenumbers.NumberParseException as e:
        print(json.dumps({"error": f"Could not parse number: {e}"}))
        return

    e164 = phonenumbers.format_number(n, phonenumbers.PhoneNumberFormat.E164)
    print(json.dumps({
        "valid": phonenumbers.is_valid_number(n),
        "possible": phonenumbers.is_possible_number(n),
        "e164": e164,
        "international": phonenumbers.format_number(n, phonenumbers.PhoneNumberFormat.INTERNATIONAL),
        "national": phonenumbers.format_number(n, phonenumbers.PhoneNumberFormat.NATIONAL),
        "countryCode": n.country_code,
        "region": phonenumbers.region_code_for_number(n),
        "location": geocoder.description_for_number(n, "en") or None,
        "type": TYPES.get(phonenumbers.number_type(n), "UNKNOWN"),
        "originalCarrier": carrier.name_for_number(n, "en") or None,
        "timezones": list(timezone.time_zones_for_number(n)),
    }))


if __name__ == "__main__":
    main()
