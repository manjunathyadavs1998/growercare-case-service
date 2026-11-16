# growercare-case-service

**What it does:**
- field cases (complaints, advice, seed claims, incidents…) on **128 shard schemas** (IN/BR × 64);
- workflow with optimistic locking, comments and partner sharing;
- SLA timers and rule-based routing;
- attachments: presigned S3 upload, malware scan, safe download.

It consumes IAM grant events to evict cached principals, and publishes case events through the outbox (Debezium → Kafka). Port **8082**.

**It depends on other services only over the network:**
- **identity-access-service** (`IAM_BASE_URL`, client-credentials token) for principals;
- **Keycloak** for identity;
- **PostgreSQL** `gc_case` (its own database), **Redis**, **Kafka** and **S3**.

## Run locally
1. Start [growercare-local-platform](https://github.com/manjunathyadavs1998/growercare-local-platform) and [identity-access-service](https://github.com/manjunathyadavs1998/growercare-identity-access-service).
2. Configure and run:
   ```bash
   cp .env.example .env                                   # every variable, documented (dev-only values)
   mvn -B package jib:dockerBuild -DskipTests             # image ghcr.io/manjunathyadavs1998/case-service
   docker compose up -d                                   # joins the "growercare" network
   ```
   The first start migrates all 128 shard schemas (about 2 minutes). Swagger UI: http://localhost:8082/swagger-ui/index.html (only with `GC_API_DOCS_ENABLED=true`).

**Configuration comes only from environment variables** (ADR-017, SPEC-PLAT-06):
- No URL, host, user, password, secret or bucket has a default.
- With variables missing, the service refuses to start and names them all.
- In AWS the values come from Secrets Manager via External Secrets.
- The S3 endpoint and keys stay empty in AWS (regional endpoint + IRSA).

## Build and test
```bash
mvn -B verify      # unit + Testcontainers ITs (Postgres, Redis, Kafka, SeaweedFS), ArchUnit, contracts, coverage >= 80 %
```
The platform libraries (`gc-parent` 1.0.0, …) come from GitHub Packages:
- **CI:** uses `GITHUB_TOKEN`.
- **Locally, either:**
  - add a PAT with `read:packages` to `~/.m2/settings.xml` (server id `github`); or
  - `mvn install -Drevision=1.0.0` in growercare-platform-libs at tag `v1.0.0`.

## Specs
- `docs/specs/case/`: this service's behaviour (access scope, lifecycle, comments and sharing, SLA and routing, attachments).
- `docs/schema.md`: the database schema.
- Cross-cutting specs and ADRs: [growercare](https://github.com/manjunathyadavs1998/growercare).
