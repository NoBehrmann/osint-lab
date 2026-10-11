# OSINT Lab

A local, single-user OSINT toolkit. You enter a target — a domain, IP, URL, email,
username, phone number, company or image — and the app detects what kind it is and offers
the tools that apply. You tick the ones you want, run them, and get one combined report.

Runs only on `http://127.0.0.1:8080`. **Do not expose it to a network.**

New here? [`guide.txt`](guide.txt) is a one-page how-to for using the tools.

---

## Requirements

- **Java 21 (JDK)** and **Maven** — on your `PATH`, or as portable copies in a sibling `.tools\`
  folder (see "Keeping everything off C:" below). `run.cmd` uses the portable ones if present and
  otherwise falls back to your `PATH`.
- **Python 3.12 or 3.13** on `PATH` as `python` — for the Python-backed tools.
- **Windows** — the helper scripts (`run.cmd`, `setup-python-tools.cmd`) are `.cmd` batch files. The
  Java and Python code is cross-platform; porting those two scripts to shell is straightforward.

Optional, per tool:

- **PhoneInfoga** — a single Go binary. Download the build for your OS from the
  [PhoneInfoga releases](https://github.com/sundowndev/phoneinfoga/releases) and put it at
  `..\.tools\phoneinfoga\phoneinfoga.exe`, or point `osintlab.phoneinfoga` (in
  `application.properties`) at wherever you keep it.
- **ExifTool** — for video, PDF and Office metadata (photos work without it). Download the Windows
  64-bit zip from [exiftool.org](https://exiftool.org), extract it to `..\.tools\exiftool\` and
  rename `exiftool(-k).exe` to `exiftool.exe` (keep the `exiftool_files` folder next to it), or point
  `osintlab.exiftool` at your copy.
- **Photo Geolocation (GeoCLIP)** — pulls in PyTorch and downloads ~1-2 GB of model weights the first
  time `setup-python-tools.cmd` runs. Don't want it? Remove `geoclip` from
  [`python-tools.txt`](python-tools.txt); every other tool still works.

### Keeping everything off C:

By convention the toolchain and caches live in folders **next to** the repo, so a clone at
`D:\work\osint-lab` uses `D:\work\.tools`, `D:\work\.m2`, `D:\work\.tmp` — nothing touches your
home directory or `C:`:

```
<parent>\
  osint-lab\     <- this repo
  .tools\        <- (optional) portable JDK 21 + Maven, PhoneInfoga, ExifTool; the Python venv and model cache
  .m2\           <- Maven repository (set in .mvn/maven.config)
  .tmp\          <- scratch, caches, temp
```

None of those sibling folders are part of the repo. If you'd rather use your normal system Maven
repository instead, delete `.mvn/maven.config`.

---

## Setup & running (Windows)

```
git clone <your-fork-url> osint-lab
cd osint-lab
copy secrets.properties.example secrets.properties   :: optional - Instagram tools / GitHub token
setup-python-tools.cmd     :: once  - creates the Python venv in ..\.tools\venv and installs the tools
run.cmd                    :: builds the app and starts it on http://127.0.0.1:8080
```

- **`setup-python-tools.cmd`** creates the virtual environment and `pip install`s everything in
  [`python-tools.txt`](python-tools.txt) (and pre-downloads the GeoCLIP model). Re-run it to update.
- **`run.cmd`** builds the jar with Maven and runs it. Stop with `Ctrl+C`.

The pure-Java tools work with `run.cmd` alone; the Python-backed tools need `setup-python-tools.cmd`
to have run once, and PhoneInfoga needs its binary in place.

---

## Tools

| Tool | Target(s) | Backed by | Notes |
|------|-----------|-----------|-------|
| DNS Records | domain, url | Java (system resolver) | A/AAAA/MX/NS/TXT/SOA + reverse |
| WHOIS / RDAP | domain, ip, url | Java | RDAP, falls back to classic WHOIS for `.eu`/`.de` etc. |
| Subdomains (CT) | domain | Java | crt.sh, falls back to CertSpotter; resolves names to IPs |
| theHarvester | domain | Python | emails, hosts, IPs from free sources |
| Lookalike Domains | domain | Python (dnstwist) | registered typo/phishing domains |
| TLS Certificate | domain, url, ip | Java | chain, validity, SANs, fingerprints, trust check |
| HTTP Inspector | domain, url | Java | headers, redirects, security headers, tech, emails on page, robots/security.txt |
| Wayback Machine | domain, url | Java (archive.org) | snapshot timeline + archived URLs |
| IP Intelligence | ip, domain | Java (ip-api) | geo, ISP, ASN + reputation links |
| Exposed Services (Shodan) | ip, domain | Java (Shodan InternetDB + CVEDB) | open ports, software, known CVEs with severity — from Shodan's own scans, nothing sent to the target |
| Network & Blacklists | ip, domain | Java (RIPEstat + DNS) | ASN owner, abuse contact, RPKI route validity, 5 spam blacklists + Spamhaus domain blocklist |
| Holehe | email | Python | which of ~120 sites an email is registered on |
| Sherlock | username, email | Python | username across 400+ sites |
| Maigret | username, email | Python | top 500 of ~3000 sites, with profile details |
| Profile Details | username | Java | join dates, bios, linked accounts: Bluesky (+ handle history), Keybase, Reddit (Arctic Shift archive), Hacker News, Mastodon, Lichess, Chess.com, Steam, Telegram |
| Code Platforms | username, email | Java | GitHub/GitLab/Codeberg profile, orgs, SSH/GPG keys, commit emails; email → GitHub account |
| Data Breaches | email, username | Java (XposedOrNot + LeakCheck) | which breaches it appears in — names and data types only, no passwords |
| Social Media Links | username, email, phone | Java | one-click checks on FB/IG/X/TikTok/LinkedIn… (no auto-check) |
| Instagram Profile | username | Java + IG session | profile, recent posts, hashtags, locations |
| Toutatis | username | Python + IG session | IG id, obfuscated recovery email/phone |
| Email Analysis | email | Java | MX, SPF, DMARC, provider, disposable check, Gravatar |
| Phone Lookup | phone | Python (libphonenumber) | validity, country, region, line type, carrier |
| PhoneInfoga | phone | Go binary | search-engine footprints: social, reputation, disposable-number, people-search sites |
| Company Registries | company name, LEI, EU VAT no. | Java (GLEIF + SEC EDGAR + VIES) | legal entity, registry number, ownership tree, US filings, VAT validity |
| Employees (CrossLinked) | company | Python + Java | LinkedIn employees via CrossLinked + DuckDuckGo |
| File Metadata | uploaded file (photo, video, PDF, Office…) | ExifTool (fallback: metadata-extractor for photos) | GPS → map, device, dates, author/company/software, video GPS tracks, file hashes |
| Photo Geolocation | uploaded file | Python (GeoCLIP) | guesses where a photo was taken from its *content* (no GPS needed); coarse, local, keyless |
| Dork Generator | domain, username, email, phone, company | Java | ready-made search-engine queries |
| Reverse Image | url, uploaded file | Java | Google Lens, Bing, Yandex, TinEye — search-by-URL for a link, or engine upload pages for a file |

Full candidate list (including tools not built in): [`tools.txt`](tools.txt).

### Optional settings

- **GitHub token** (`osintlab.github.token` in `secrets.properties`): Code Platforms works without
  one, but GitHub then allows only 60 requests/hour (~7 lookups). A free fine-grained token with
  **no permissions** raises that to 5000.
- **SEC contact** (`osintlab.sec.contact` in `application.properties`): SEC EDGAR requires a contact
  address in the User-Agent; put your own if you use Company Registries a lot.

### Instagram session

Toutatis and Instagram Profile need your own Instagram `sessionid` cookie, because
Instagram rate-limits anonymous access. Two ways to provide it:

- **Load from browser** button (shown when you enter a username): reads only the
  `instagram.com` `sessionid` from your own browser (Firefox is most reliable; current
  Chrome/Edge encrypt cookies and often block it). The token is kept in server memory and
  never sent back to the page.
- **Manually**: copy `secrets.properties.example` to `secrets.properties`, paste the cookie in, and
  restart. `secrets.properties` is gitignored, so your token never gets committed.

Use a secondary account — Instagram may flag accounts used for lookups.

### Files: photos, videos, documents

Click **File** and pick any file up to **1 GB**. It is uploaded once (with a progress bar), stored on
disk under `..\.tmp\uploads` and shared by every tool you tick. It is never loaded into memory, so a
500 MB video works fine (tested: RAM stays flat, upload ~2 s, analysis ~7 s on an SSD). Uploads are
deleted after 30 minutes, when you pick another file, or when the app restarts.

**File Metadata** reads, depending on the file:
- **Photos** — GPS, camera, lens, serial numbers, software, dates.
- **Videos** — GPS (phones store it), recording device, creation time (QuickTime times are UTC), and
  GPS *tracks* embedded by dashcams, drones and action cams.
- **PDF / Office** — author, last editor, company, creating software, edit time, revision, template.

Files shared via WhatsApp, Instagram, TikTok & co. are usually re-encoded and stripped of all this.

### Where a photo was taken

Upload a photo and three independent methods appear as checkboxes — tick whichever you want:

1. **File Metadata** — reads the GPS tags. Exact when present, but most social-media images have
   it stripped.
2. **Photo Geolocation** — GeoCLIP guesses the location from visual content (architecture, flora,
   signage) when there's no GPS. Runs locally in the venv, nothing leaves the machine; the estimate
   is coarse (often region/country) and is only a lead. First use downloads ~1-2 GB of model weights
   into `..\.tools\hf-cache` (pre-fetched by `setup-python-tools.cmd`).
3. **Reverse Image** — links to Google Lens / Yandex / Bing / TinEye. No free API returns match data,
   so for an uploaded file you open an engine and drop the image in (nothing is uploaded by the lab).

---

## How it works

- **Spring Boot** (Java 21) serving a small vanilla-JS frontend from `src/main/resources/static`.
- Each tool is an `OsintTool` bean (`run(target)` → JSON). A tool appears in the UI as soon
  as its bean exists; the catalog lives in
  [`ToolRegistry`](src/main/java/com/osintlab/tools/ToolRegistry.java).
- Inputs are validated in [`Inputs`](src/main/java/com/osintlab/support/Inputs.java) before
  use. Python tools are launched with no shell and arguments after `--`, so input can't be
  read as options.
- [`ToolExecutor`](src/main/java/com/osintlab/tools/ToolExecutor.java) caps concurrency
  (per-tool and global), caches results (per-tool TTL, bypassable with "refresh"), and
  returns "busy" rather than overloading.
- Results render generically from the JSON; bulky sections start collapsed.

### Adding a tool

1. Create a class implementing `OsintTool` (or `FileTool` for uploads) as a `@Component`.
2. Add a matching entry to `ToolRegistry` (same `id`), listing the target types it accepts.

That's it — the endpoint, concurrency limits, caching and UI come for free.

---

## Layout

The repo (committed):

```
osint-lab\
  src\                      Java + static frontend
  scripts\                  Python helper scripts (phone, toutatis, ig_cookie, geolocate)
  .mvn\                     Maven config (portable, relative paths)
  run.cmd                   build & start
  setup-python-tools.cmd    create venv + install Python tools
  python-tools.txt          Python tools to install
  secrets.properties.example  template for the (gitignored) secrets.properties
  tools.txt                 full catalogue of candidate OSINT tools
  guide.txt                 short how-to for using the tools
```

Created locally, **not** committed (sibling folders, see "Keeping everything off C:"):

```
.tools\   portable JDK, Maven, PhoneInfoga, ExifTool, the Python venv, the GeoCLIP model cache
.m2\      Maven repository
.tmp\     scratch, caches, Tomcat basedir, uploaded files (auto-deleted)
```

---

## Scope & legal

- Localhost, single user. Bound to `127.0.0.1` on purpose.
- Passive OSINT only — no port scanning, no mailbox probing, no brute force.
- Some tools scrape sites or use undocumented endpoints and **will break** when those
  change. Search engines rate-limit or bot-check automated queries; tools report when that
  happens rather than failing silently.
- Scraping Instagram/LinkedIn breaks their terms of service, and collecting personal data
  falls under the GDPR. Use only for your own investigations, CTFs, or authorised
  engagements.
