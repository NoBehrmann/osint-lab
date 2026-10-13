# OSINT Lab

A local OSINT toolkit with a web interface. Enter a domain, IP, URL, email address, username,
phone number or company name, or upload a file. The app detects the target type, lists the tools
that apply, runs the ones you pick and puts the results into one report.

It runs on `http://127.0.0.1:8080` and is meant for a single user on their own machine. It has no
login, so don't expose it to a network.

For a short how-to, see [guide.txt](guide.txt).

## Requirements

- Java 21 (JDK) and Maven
- Python 3.12 or 3.13, available as `python`
- Windows for the helper scripts (`run.cmd`, `setup-python-tools.cmd`). The Java and Python code
  itself is cross-platform.

`run.cmd` uses a portable JDK and Maven from a `.tools` folder next to the repo if it finds one
(`..\.tools\jdk-21*`, `..\.tools\apache-maven-*`), and the ones on your `PATH` otherwise.

Optional:

- **PhoneInfoga**: download the binary from the
  [releases page](https://github.com/sundowndev/phoneinfoga/releases) and put it at
  `..\.tools\phoneinfoga\phoneinfoga.exe`.
- **ExifTool**: needed for video, PDF and Office metadata (photos work without it). Download the
  Windows 64-bit zip from [exiftool.org](https://exiftool.org), extract it to `..\.tools\exiftool\`
  and rename `exiftool(-k).exe` to `exiftool.exe`. Keep the `exiftool_files` folder next to it.
- **GeoCLIP** (Photo Geolocation): installed by `setup-python-tools.cmd`. It pulls in PyTorch and
  downloads 1-2 GB of model weights. To skip it, remove `geoclip` from
  [python-tools.txt](python-tools.txt).

All paths can be changed in [application.properties](src/main/resources/application.properties).

## Setup

```
git clone https://github.com/<you>/osint-lab.git
cd osint-lab
setup-python-tools.cmd
run.cmd
```

`setup-python-tools.cmd` creates a Python virtual environment in `..\.tools\venv` and installs the
tools from `python-tools.txt`. Run it once, and again whenever you want to update the tools.

`run.cmd` builds the app and starts it. Open `http://127.0.0.1:8080` and stop it with `Ctrl+C`.

The Java tools work right away. The Python tools need `setup-python-tools.cmd` to have run, and
PhoneInfoga needs its binary.

Folders created next to the repo (not part of it):

```
.tools\   portable JDK and Maven (optional), PhoneInfoga, ExifTool, Python venv, model cache
.m2\      Maven repository (set in .mvn/maven.config, delete that file to use your default one)
.tmp\     temp files, caches and uploads
```

## Tools

| Tool | Input | Source | What you get |
|------|-------|--------|--------------|
| DNS Records | domain, URL | Java | A, AAAA, MX, NS, TXT, SOA and reverse DNS |
| WHOIS / RDAP | domain, IP, URL | Java | registration data, with classic WHOIS for ccTLDs like `.de` and `.eu` |
| Subdomains (CT) | domain | crt.sh, CertSpotter | subdomains from Certificate Transparency logs, resolved to IPs |
| theHarvester | domain | Python | emails, hosts and IPs from public sources |
| Lookalike Domains | domain | dnstwist | registered typo and phishing domains |
| TLS Certificate | domain, URL, IP | Java | chain, validity, SANs, fingerprints, trust check |
| HTTP Inspector | domain, URL | Java | headers, redirects, security headers, technologies, emails on the page, robots.txt, security.txt |
| Wayback Machine | domain, URL | archive.org | snapshot timeline and archived URLs |
| IP Intelligence | IP, domain | ip-api.com | location, ISP, ASN, reputation links |
| Exposed Services | IP, domain | Shodan InternetDB | open ports, software and known CVEs from Shodan's own scans |
| Network & Blacklists | IP, domain | RIPEstat, DNS | ASN owner, abuse contact, RPKI status, spam blacklists |
| Holehe | email | Python | which of about 120 sites an email is registered on |
| Sherlock | username, email | Python | username on 400+ sites |
| Maigret | username, email | Python | username on the top 500 of about 3000 sites, with profile details |
| Profile Details | username | public APIs | join dates, bios and linked accounts on Bluesky, Keybase, Reddit (Arctic Shift), Hacker News, Mastodon, Lichess, Chess.com, Steam, Telegram |
| Code Platforms | username, email | GitHub, GitLab, Codeberg | profile, orgs, SSH/GPG keys, commit emails; finds the GitHub account for an email |
| Data Breaches | email, username | XposedOrNot, LeakCheck | breach names and exposed data types (no passwords) |
| Social Media Links | username, email, phone | links | direct links to check Facebook, Instagram, X, TikTok, LinkedIn and others by hand |
| Instagram Profile | username | Instagram session | profile, recent posts, hashtags, locations |
| Toutatis | username | Python, Instagram session | Instagram ID, masked recovery email and phone |
| Email Analysis | email | Java | MX, SPF, DMARC, provider, disposable check, Gravatar |
| Phone Lookup | phone | libphonenumber | validity, country, region, line type, carrier |
| PhoneInfoga | phone | PhoneInfoga | search queries for social media, reputation and people-search sites |
| Company Registries | company name, LEI, EU VAT number | GLEIF, SEC EDGAR, VIES | legal entity, registry number, ownership, US filings, VAT check |
| Employees | company | CrossLinked | LinkedIn employees found through search engines |
| File Metadata | file | ExifTool | GPS with map link, device, dates, author, software, video GPS tracks, file hashes |
| Photo Geolocation | image | GeoCLIP | estimated location from the image content, runs locally |
| Dork Generator | domain, username, email, phone, company | links | ready-made search engine queries |
| Reverse Image | URL, image | links | Google Lens, Bing, Yandex and TinEye |

[tools.txt](tools.txt) lists more tools that could be added.

## Configuration

Copy `secrets.properties.example` to `secrets.properties` (it is in `.gitignore`) for these
optional settings:

- `osintlab.github.token`: Code Platforms works without a token, but GitHub then allows only 60
  requests per hour, which is about 7 lookups. A fine-grained token with no permissions raises that
  to 5000.
- `osintlab.instagram.sessionid`: needed by Instagram Profile and Toutatis. Instead of setting it
  here you can click "Load from browser" in the app, which reads the `sessionid` cookie from your
  own browser (Firefox works best; current Chrome and Edge often block it). The token stays in
  server memory and is never sent to the page. Use a secondary account, because Instagram may flag
  accounts used for lookups.

In `application.properties`, `osintlab.sec.contact` is the contact address sent to SEC EDGAR, which
requires one. Set it to your own address if you use Company Registries a lot.

## Files

The File button accepts any file up to 1 GB. The file is uploaded once, stored in `..\.tmp\uploads`
and shared by all tools you tick. Uploads are deleted after 30 minutes, when you choose another
file, or when the app restarts.

File Metadata reads:

- photos: GPS, camera, lens, serial numbers, software, dates
- videos: GPS, recording device, creation time (UTC), and GPS tracks from dashcams, drones and
  action cameras
- PDF and Office files: author, last editor, company, software, editing time, revision

For finding where a photo was taken there are three options. File Metadata gives exact GPS
coordinates if the photo still has them (social media usually removes them). Photo Geolocation
estimates the location from what the picture shows, usually down to the region or country. Reverse
Image links to the search engines; for an uploaded file you drop the image into the engine
yourself, since none of them offers a free API.

## How it works

The backend is Spring Boot (Java 21), and the frontend is plain HTML, CSS and JavaScript in
`src/main/resources/static`.

- Every tool is a Spring bean that implements `OsintTool` (or `FileTool` for uploads) and returns
  a map that is sent to the browser as JSON. [ToolRegistry](src/main/java/com/osintlab/tools/ToolRegistry.java)
  holds the catalog shown in the UI.
- Input is validated in [Inputs](src/main/java/com/osintlab/support/Inputs.java) before it is used.
  External programs are started without a shell, and arguments come after `--`, so input can't be
  read as an option.
- [ToolExecutor](src/main/java/com/osintlab/tools/ToolExecutor.java) limits how many tools run at
  once and caches results. "refresh" in the UI skips the cache.

To add a tool, create a `@Component` class that implements `OsintTool` and add an entry with the
same id to `ToolRegistry`.

## Limitations and legal

- Passive OSINT only: no port scanning, mailbox probing or brute forcing.
- Some tools depend on scraping or undocumented endpoints and will break when those sites change.
  Search engines sometimes block automated queries; the affected tool reports it.
- Scraping Instagram and LinkedIn is against their terms of service, and collecting personal data
  falls under the GDPR. Use this only for your own research, CTFs or authorised engagements.
