"""Estimate where a photo was taken from its visual content (not metadata), using GeoCLIP.

GeoCLIP matches the image against a large gallery of GPS locations and returns the most likely
coordinates. It reasons from visible cues (architecture, vegetation, signage, road markings), so it
works even when EXIF GPS has been stripped. Accuracy is coarse (often region/country level) and it is
only a guess; always corroborate with the other tools.

Usage: python geolocate.py <image_path>
Prints JSON: {"predictions": [{"lat", "lon", "confidence"}, ...]} or {"error": "..."}.
"""
import json
import sys


def main():
    args = [a for a in sys.argv[1:] if a != "--"]
    if not args:
        print(json.dumps({"error": "No image path given"}))
        return
    path = args[0]

    try:
        from geoclip import GeoCLIP
    except Exception as e:  # noqa: BLE001  (report any import problem to the caller as JSON)
        print(json.dumps({"error": f"geoclip is not installed, run setup-python-tools.cmd ({e})"}))
        return

    try:
        model = GeoCLIP()
        top_gps, top_prob = model.predict(path, top_k=5)
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"error": f"GeoCLIP could not read the image: {e}"}))
        return

    predictions = []
    for (lat, lon), prob in zip(top_gps.tolist(), top_prob.tolist()):
        predictions.append({
            "lat": round(float(lat), 5),
            "lon": round(float(lon), 5),
            "confidence": round(float(prob), 4),
        })
    print(json.dumps({"predictions": predictions}))


if __name__ == "__main__":
    main()
