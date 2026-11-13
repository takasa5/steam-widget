# Development

This document covers everything needed to build and run Steam Widget locally.

---

## Tech Stack

- **Java 21**
- **Spring Boot** (Web, Data JPA, Thymeleaf, Actuator)
- **PostgreSQL**
- **Maven** (via the included `mvnw` wrapper)

---

## Prerequisites

- JDK 21
- A running PostgreSQL instance
- A [Steam Web API key](https://steamcommunity.com/dev/apikey)

---

## Configuration

The app reads its configuration from `src/main/resources/application.properties`. At minimum you need to set:

```properties
# JDBC URL for your local Postgres instance
spring.datasource.url=jdbc:postgresql://localhost:5432/steamwidget

# Your Steam Web API key
steam.api.key=YOUR_STEAM_API_KEY
```

Rather than editing `application.properties` directly, prefer overriding these via environment variables or `-D` system properties so local secrets never get committed:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/steamwidget
export STEAM_API_KEY=YOUR_STEAM_API_KEY
```

Other tunable settings (with defaults):

| Property | Default | Description |
|---|---|---|
| `hit.ip.retention.days` | `90` | Days after which IP addresses in hit records are nullified. |
| `hit.raw.retention.days` | `730` | Days after which entire raw hit records are deleted. Must be greater than `hit.ip.retention.days`. |

The database schema is managed by Hibernate/JPA against the entities in `src/main/java/codes/sharky/steamwidget/entity`; no separate migration step is required for local development.

---

## Fonts for Non-Latin Text

Widget images are drawn with Arial, which has no glyphs for Japanese, Chinese, Korean, Thai, Arabic and many other scripts. Text in those scripts (profile names, game names, "Now playing ...") is drawn character by character with a fallback font, so it no longer renders as empty boxes.

Fallback fonts are searched in this order:

1. Fonts bundled on the classpath: put the files in `src/main/resources/fonts/` and list their file names, one per line, in `src/main/resources/fonts/fallback.txt`.
2. Fonts in a directory set with `-Dwidget.fonts.dir=/path/to/fonts` or the `WIDGET_FONTS_DIR` environment variable.
3. Fonts installed on the machine. Noto fonts are preferred when several fonts can draw a character.

On a server, installing the Noto packages is usually enough. For Debian/Ubuntu-based images:

```bash
apt-get install -y fonts-noto-core fonts-noto-cjk
```

`fonts-noto-cjk` covers Japanese, Simplified/Traditional Chinese and Korean; `fonts-noto-core` covers most other scripts (Thai, Arabic, Hebrew, Devanagari, ...). Without any of these, the widget still renders, and characters no font can display fall back to the old empty boxes.

---

## Running Locally

Build and run with the Maven wrapper — no local Maven install required:

```bash
./mvnw spring-boot:run
```

Or package and run the jar directly:

```bash
./mvnw clean package
java -jar target/steamwidget-*.jar
```

The app starts on `http://localhost:8080` by default. The static frontend pages (`index.html`, `tracking.html`, `playing-stats.html`, `profile-metrics.html`) are served from `src/main/resources/static`, and the JSON/image API described in [API.md](API.md) is available under `/widget`, `/api`, and `/metric`.

---

## Running Tests

```bash
./mvnw test
```

---

## Project Layout

```
src/main/java/codes/sharky/steamwidget/
├── controller/   # REST & web endpoints (widget, tracking, metrics, OpenID login)
├── entity/       # JPA entities and composite IDs
├── repository/   # Spring Data repositories
├── component/    # Steam Web API client, Steam OpenID integration
├── config/       # Spring configuration (async, Steam API client)
├── model/        # Enums/DTOs shared across the app
└── scheduled/    # Scheduled jobs (caching, retention cleanup)

src/main/resources/
├── static/       # Frontend pages served as-is
├── templates/    # Thymeleaf templates
└── application.properties
```
