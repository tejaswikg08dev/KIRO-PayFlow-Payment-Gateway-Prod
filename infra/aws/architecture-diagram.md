# PayFlow - AWS Architecture Diagram

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                    INTERNET                                      │
└───────────────────────────────────────┬─────────────────────────────────────────┘
                                        │
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              AWS CLOUD (ap-south-1)                               │
│                                                                                  │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │                            Route 53 (DNS)                                  │  │
│  │          api.payflow.com → ALB    portal.payflow.com → CloudFront          │  │
│  └────────────────────────────┬───────────────────────────────┬───────────────┘  │
│                               │                               │                  │
│                               ▼                               ▼                  │
│  ┌─────────────────────────────────────┐    ┌──────────────────────────────┐    │
│  │         Application Load Balancer    │    │        CloudFront CDN         │    │
│  │         (HTTPS / WAF Protected)      │    │     (Static Assets + SPA)     │    │
│  └──────────────────┬──────────────────┘    └───────────────┬──────────────┘    │
│                     │                                       │                    │
│     ┌───────────────┼───────────────────────────┐          │                    │
│     │           PUBLIC SUBNETS                   │          │                    │
│     │                                           │          │                    │
│     │    ┌──────────────────────────────┐       │          ▼                    │
│     │    │       NAT Gateway            │       │   ┌────────────────┐          │
│     │    └──────────────────────────────┘       │   │   S3 Buckets   │          │
│     └───────────────┬───────────────────────────┘   │  - Portal      │          │
│                     │                               │  - Checkout     │          │
│                     ▼                               └────────────────┘          │
│     ┌───────────────────────────────────────────────────────────────────┐       │
│     │                    PRIVATE SUBNETS (App Tier)                       │       │
│     │                                                                    │       │
│     │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐               │       │
│     │  │  Service     │  │   Config    │  │    API      │               │       │
│     │  │  Registry    │  │   Server    │  │  Gateway    │               │       │
│     │  │  (8761)      │  │  (8888)     │  │  (8080)     │               │       │
│     │  └─────────────┘  └─────────────┘  └──────┬──────┘               │       │
│     │                                            │                       │       │
│     │         ┌──────────────────────────────────┼──────────────┐       │       │
│     │         │                                  │              │       │       │
│     │         ▼                                  ▼              ▼       │       │
│     │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │       │
│     │  │  Identity   │  │  Merchant   │  │  Payment    │              │       │
│     │  │  Service    │  │  Service    │  │  Service    │              │       │
│     │  │  (8081)     │  │  (8082)     │  │  (8083)     │              │       │
│     │  └─────────────┘  └─────────────┘  └─────────────┘              │       │
│     │                                                                    │       │
│     │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │       │
│     │  │  Routing    │  │ Settlement  │  │  Webhook    │              │       │
│     │  │  Service    │  │  Service    │  │  Service    │              │       │
│     │  │  (8084)     │  │  (8085)     │  │  (8086)     │              │       │
│     │  └─────────────┘  └─────────────┘  └─────────────┘              │       │
│     │                                                                    │       │
│     │  ┌─────────────┐                                                  │       │
│     │  │Notification │                                                  │       │
│     │  │  Service    │                                                  │       │
│     │  │  (8087)     │                                                  │       │
│     │  └─────────────┘                                                  │       │
│     └───────────────────────────────────────────────────────────────────┘       │
│                     │                                                            │
│                     ▼                                                            │
│     ┌───────────────────────────────────────────────────────────────────┐       │
│     │                   DATABASE SUBNETS (Data Tier)                      │       │
│     │                                                                    │       │
│     │  ┌─────────────────────┐  ┌──────────────────┐                   │       │
│     │  │   RDS PostgreSQL    │  │  ElastiCache     │                   │       │
│     │  │   (Multi-AZ)        │  │  Redis Cluster   │                   │       │
│     │  │                     │  │                  │                   │       │
│     │  │  - payflow_identity │  │  - Session cache │                   │       │
│     │  │  - payflow_merchant │  │  - Rate limiting │                   │       │
│     │  │  - payflow_payment  │  │  - Idempotency   │                   │       │
│     │  │  - payflow_settlement│  │                  │                   │       │
│     │  └─────────────────────┘  └──────────────────┘                   │       │
│     │                                                                    │       │
│     │  ┌─────────────────────┐  ┌──────────────────┐                   │       │
│     │  │     Amazon MSK      │  │    DynamoDB      │                   │       │
│     │  │   (Kafka Cluster)   │  │                  │                   │       │
│     │  │                     │  │  - webhook_logs  │                   │       │
│     │  │  - 3 brokers        │  │  - routing_mets  │                   │       │
│     │  │  - 18 topics        │  │  - event_logs    │                   │       │
│     │  └─────────────────────┘  └──────────────────┘                   │       │
│     └───────────────────────────────────────────────────────────────────┘       │
│                                                                                  │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │                         SUPPORTING SERVICES                                 │  │
│  │                                                                             │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │  │
│  │  │  CloudWatch  │  │   X-Ray      │  │   Secrets    │  │     ECR      │   │  │
│  │  │  Logs/Alarms │  │  Tracing     │  │   Manager    │  │  (Registry)  │   │  │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘   │  │
│  │                                                                             │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │  │
│  │  │     SNS      │  │     SQS      │  │     SES      │  │   GuardDuty  │   │  │
│  │  │  (Events)    │  │  (Queues)    │  │  (Email)     │  │  (Security)  │   │  │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘   │  │
│  └────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

## Data Flow

```
┌──────────┐     ┌──────┐     ┌─────────┐     ┌──────────┐     ┌────────────┐
│ Customer │────▶│ ALB  │────▶│   API   │────▶│ Payment  │────▶│  Routing   │
│ Browser  │     │      │     │ Gateway │     │ Service  │     │  Service   │
└──────────┘     └──────┘     └─────────┘     └────┬─────┘     └─────┬──────┘
                                                    │                  │
                                                    │                  ▼
                                                    │          ┌────────────┐
                                                    │          │   Bank     │
                                                    │          │ Acquirer   │
                                                    │          └────────────┘
                                                    │
                                                    ▼
                                              ┌──────────┐
                                              │  Kafka   │
                                              │ (Events) │
                                              └────┬─────┘
                                                   │
                              ┌─────────────────────┼─────────────────────┐
                              │                     │                     │
                              ▼                     ▼                     ▼
                       ┌────────────┐       ┌────────────┐       ┌────────────┐
                       │ Settlement │       │  Webhook   │       │Notification│
                       │  Service   │       │  Service   │       │  Service   │
                       └────────────┘       └─────┬──────┘       └─────┬──────┘
                                                  │                     │
                                                  ▼                     ▼
                                           ┌────────────┐       ┌────────────┐
                                           │  Merchant  │       │  Customer  │
                                           │  Webhook   │       │   Email    │
                                           │  Endpoint  │       │   / SMS    │
                                           └────────────┘       └────────────┘
```

## Service Communication Map

```
┌───────────────────────────────────────────────────────────────────┐
│                    SYNCHRONOUS (REST/gRPC)                         │
│                                                                   │
│  API Gateway ──────▶ Identity Service (auth validation)           │
│  API Gateway ──────▶ All Services (request routing)               │
│  Payment Service ──▶ Routing Service (route selection)            │
│  Routing Service ──▶ Bank Simulator (payment processing)          │
│  Settlement ───────▶ Payment Service (fetch transactions)         │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────┐
│                    ASYNCHRONOUS (Kafka Events)                     │
│                                                                   │
│  Payment Service ──publish──▶ payment.captured                    │
│  Payment Service ──publish──▶ payment.refunded                    │
│  Settlement ───────publish──▶ settlement.completed                │
│                                                                   │
│  Webhook Service ◀──consume── payment.*, settlement.*             │
│  Notification ───◀──consume── payment.*, settlement.*             │
│  Settlement ─────◀──consume── payment.captured                    │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

## Security Zones

```
┌─────────────────────────────────────────────────────────────┐
│  ZONE 1: PUBLIC (Internet-facing)                            │
│  - ALB (HTTPS only, WAF protected)                           │
│  - CloudFront (Static assets)                                │
│  - Route 53 (DNS)                                            │
├─────────────────────────────────────────────────────────────┤
│  ZONE 2: DMZ (Semi-trusted)                                  │
│  - API Gateway (JWT validation, rate limiting)               │
│  - Service Registry (internal discovery)                     │
├─────────────────────────────────────────────────────────────┤
│  ZONE 3: APPLICATION (Trusted)                               │
│  - Business services (identity, merchant, payment, etc.)     │
│  - Inter-service communication                               │
├─────────────────────────────────────────────────────────────┤
│  ZONE 4: DATA (Most restricted)                              │
│  - RDS PostgreSQL (encrypted at rest)                        │
│  - ElastiCache Redis (encrypted in transit)                  │
│  - Amazon MSK (TLS enabled)                                  │
│  - DynamoDB (encrypted by default)                           │
└─────────────────────────────────────────────────────────────┘
```
