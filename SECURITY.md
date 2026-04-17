# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.0.x   | Yes       |
| < 1.0   | No        |

## Security Model

BPM is a **local-only, passive observer** that runs inside JMeter. The plugin opens no network
sockets and performs no telemetry. CDP traffic piggybacks on the WebDriver Sampler's existing
Chrome connection; BPM adds no new network traffic.

### Threat Surface

| Area                         | Design                                                                                                                                                                                                          |
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **File I/O**                 | JSONL / CSV writes to paths from `-Jbpm.output`, GUI field, or `bpm.properties` — resolved at JMeter invocation, not from network input.                                                                        |
| **Console sanitization**     | Default ON (`security.sanitize=true`). Redacts Bearer tokens, API keys, and JWTs from console messages before JSONL write.                                                                                      |
| **Reflection**               | Limited to `Class.forName` for lazy Selenium loading. No JMeter-internal reflection.                                                                                                                            |
| **Serialization**            | Jackson defaults — no polymorphic deserialization.                                                                                                                                                              |
| **HTML report CDN fallback** | Chart.js + xlsx-js-style bundled in the JAR; if missing from classpath, generated HTML references `cdnjs.cloudflare.com` / `cdn.jsdelivr.net` — loaded by the user's browser at view time, never by the plugin. |
| **Properties**               | Read via `JMeterUtils.getProperty()`. Type-validated with hardcoded defaults on parse failure.                                                                                                                  |

### What BPM Does NOT Protect Against

- **Secrets outside the sanitizer's patterns**: default patterns cover Bearer tokens, API keys, and
  JWTs. Other secrets (custom auth schemes, query-string passwords, session IDs, cookies) are not
  masked. Review JSONL before sharing externally.
- **URLs in the network waterfall**: recorded URLs and resource paths are not redacted. Test plans
  that hit endpoints with secrets in the path or query string will leak those into JSONL.
- **Untrusted test plans**: JMeter runs arbitrary Java via BeanShell / JSR223. BPM is a passive
  observer — it does not sandbox the test plan.

## Reporting a Vulnerability

1. **Do not** open a public GitHub issue.
2. Use
   GitHub's [private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
   on this repository. Include a description, reproduction steps, and potential impact.

## Dependencies

All runtime dependencies are `provided` scope (on JMeter's classpath):

| Dependency             | Purpose                                 |
|------------------------|-----------------------------------------|
| ApacheJMeter_core      | JMeter listener + lifecycle integration |
| selenium-chrome-driver | Chrome DevTools Protocol sessions       |
| jackson-databind       | JSONL serialization / deserialization   |

Thin JAR, no shaded transitive dependencies. Updates tracked via Dependabot.
