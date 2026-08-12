# Provenance

Provenance is a small service that periodically polls an RSS feed, ingests the articles it finds, and exposes them
over a JSON API — instrumented end-to-end with Dropwizard metrics so the pipeline can be observed in Prometheus and
Grafana.

### How it works

- **`EndpointWorkFinder` / `EndpointWorker`** (`components/endpoints`) — on a schedule, the work finder looks up
  ready endpoints (currently the InfoQ RSS feed, `https://feed.infoq.com/`) and the worker fetches each one, parses
  the RSS/XML response, and saves the resulting articles.
- **`ArticleDataGateway` / `ArticlesController`** (`components/articles`) — holds the ingested articles in memory and
  serves them over HTTP.
- **`WorkScheduler`** (`components/workflow-support`) — drives the polling loop on a fixed interval.
- **`BasicApp` / `BasicHandler` / `RestTemplate`** (`components/rest-support`) — small Kotlin foundation the app is
  built on: an embedded Jetty server, a handler base class for JSON endpoints, and an HTTP client used to call out to
  the RSS feed.
- **`MetricsController` / `HealthCheck`** (`components/metrics-support`) — exposes Dropwizard metrics to Prometheus
  and a basic health check.

### Endpoints

| Method | Path         | Description                              |
|--------|--------------|-------------------------------------------|
| GET    | `/articles`  | All ingested articles                     |
| GET    | `/available` | Articles currently marked as available    |
| GET    | `/metrics`   | Prometheus-formatted metrics              |
| GET    | `/health`    | Health check                              |

### Quick start

Build the project:

```bash
./gradlew clean build
```

Run the server:

```bash
java -jar applications/provenance-server/build/libs/provenance-server-1.0-SNAPSHOT.jar
```

The server listens on port `8881` by default (override with the `PORT` environment variable). Once started, the
endpoint worker will poll the configured RSS feed every 300 seconds and populate `/articles` and `/available`.

```bash
curl http://localhost:8881/articles
```

On Windows (PowerShell), use `curl.exe` to get the real curl binary rather than the `Invoke-WebRequest` alias, and
pass an explicit `Accept` header — unlike `Invoke-WebRequest`, plain `curl.exe` doesn't send one by default, and the
server returns a 500 without it:

```powershell
curl.exe -H "Accept: application/json" http://localhost:8881/articles
```

### Metrics: Prometheus

We use [Prometheus](https://prometheus.io/) to store metrics emitted by the service, including request rates
(`article-requests`, `article-available-requests`) and the current article count.

Install Prometheus and point it at the app's metrics endpoint.

```bash
brew install prometheus
```

On Windows, download the Prometheus zip from [prometheus.io/download](https://prometheus.io/download/) and extract
it instead.

Modify `/usr/local/etc/prometheus.yml` to match the example below (for Homebrew on Apple Silicon, use
`/opt/homebrew/etc/prometheus.yml`). On Windows, edit `prometheus.yml` in the folder you extracted.

```yaml
  - job_name: 'dropwizard'
    metrics_path: '/metrics'
    scrape_interval: 5s
    scheme: http
    static_configs:
      - targets: [ 'localhost:8881' ]
```

Restart Prometheus.

```bash
brew services restart prometheus
```

On Windows, there's no service to restart — just (re)run the binary from the extracted folder, pointing it at the
config file:

```powershell
.\prometheus.exe --config.file=prometheus.yml
```

Upon success, you should see the Dropwizard endpoint `http://localhost:8881/metrics` **UP** on the Prometheus
[Status Targets](http://localhost:9090/targets) page.

![Prometheus target](docs/images/prometheus.png)

### Metrics: Grafana

We use [Grafana](https://grafana.com/) to query, visualize, and alert on the metrics stored in Prometheus.

Install and run Grafana.

```bash
brew install grafana
brew services restart grafana
```

On Windows, download the installer (or standalone zip) from
[grafana.com/grafana/download](https://grafana.com/grafana/download?platform=windows). The installer sets up a
Windows service that starts automatically; if you use the zip instead, run it directly from the extracted folder.
Recent Grafana releases (v10+) ship a single `grafana.exe` (the old separate `grafana-server.exe` /
`grafana-cli.exe` binaries were merged into it and are now removed), so start the server via the `server`
subcommand:

```powershell
.\bin\grafana.exe server
```

Use the [web application](http://localhost:3000) to configure a Prometheus data source.

![Prometheus data source](docs/images/data-source.png)

Add the data source at `http://localhost:3000/datasources/new`, using `http://localhost:9090` as the Prometheus URL.

Create a new dashboard and graph the `article_requests_total` metric. Use the query below to display requests per
second.

```
irate(article_requests_total[5m])
```

![Grafana query](docs/images/query.png)

Drive traffic to the articles endpoint to generate data for the dashboard:

```bash
while [ true ]; do for i in {1..10}; do curl -v -H "Accept: application/json" http://localhost:8881/articles; done; sleep 5; done
```

On Windows (PowerShell):

```powershell
while ($true) {
    1..10 | ForEach-Object { curl.exe -v -H "Accept: application/json" http://localhost:8881/articles }
    Start-Sleep -Seconds 5
}
```

Your dashboard should start recording data.

![Grafana dashboard](docs/images/dashboard.png)

### Project layout

```
applications/provenance-server   # entry point, wires everything together
components/articles              # article storage and HTTP endpoints
components/endpoints              # RSS endpoint polling: work finder, worker, task
components/rss-support           # RSS/XML data model
components/workflow-support       # generic scheduled work-finder/worker framework
components/rest-support          # embedded Jetty app + handler base class + HTTP client
components/metrics-support       # Prometheus metrics endpoint + health check
```

### Why metrics endpoints matter

A metrics endpoint is a URL an application exposes (conventionally `/metrics`) that reports its own internal state
as numbers — request counts, latencies, error rates, queue depths, business counters — in a machine-readable
format. Rather than pushing data anywhere, the app just answers "here's my current state" when asked; a collector
like Prometheus polls it on an interval and stores the resulting time series.

In this project, `MetricsController` (`components/metrics-support`) handles `GET /metrics` and dumps everything in a
Prometheus `CollectorRegistry` as plain text. The app registers counters like `article-requests` and
`article-available-requests`, so every scrape gives Prometheus a fresh snapshot of how often those endpoints get
hit.

Logs tell you what happened to one request; metrics tell you the shape of behavior across all of them — trends,
rates, outliers — cheaply, because they're pre-aggregated numbers instead of raw text parsed at query time. That
makes them central to:

- **Observability without guessing** — see CPU, memory, throughput, and error rates without SSHing into a box or
  grepping logs.
- **Alerting** — a threshold on a metric (e.g. "error rate > 1% for 5m") is what pages someone, long before a user
  files a complaint.
- **Capacity and trend analysis** — metrics accumulate over weeks/months, surfacing gradual leaks, growth trends, or
  the effect of a deploy that a single log line never would.

They're particularly crucial for:

- **Incident response** — dashboards built on metrics pinpoint which service/instance/endpoint is unhealthy in
  seconds, versus digging through logs.
- **Autoscaling** — systems like Kubernetes HPA or cloud autoscaling groups scale on metrics (CPU, request rate,
  custom app metrics), not logs.
- **SLOs/SLAs** — you can't prove "99.9% uptime" or "p99 latency under 200ms" without a continuous metric stream to
  compute it from.
- **Performance regression detection** — comparing latency/error metrics before and after a deploy catches
  regressions that unit tests miss, since they only show up under real traffic shape.
- **Capacity planning** — trending request volume and resource usage over months shows when infrastructure needs to
  scale, before an outage forces the issue.

The same `/metrics` output serves both development and operations differently: developers use it to see how code
behaves under real traffic — which endpoints are hot, where latency creeps in, whether a new feature changed error
rates — feedback that's largely invisible in a local dev environment or test suite. Operations/SRE use the same data
for uptime, alerting, and capacity decisions, reading dashboards instead of code or logs to know a service is
degrading, with the same numbers doubling as evidence during postmortems.

© 2022 by Initial Capacity, Inc. All rights reserved.
