# PayFlow Payment Gateway — Project Structure

See the complete project structure in the provided PROJECT-STRUCTURE.md document.

## Quick Reference

```
payflow-payment-gateway/
├── backend/                    ← Java 17 + Spring Boot 3.2.5 (12 modules)
│   ├── pom.xml                 ← Parent POM
│   ├── common-lib/             ← Shared DTOs, events, exceptions, utils
│   ├── service-registry/       ← Eureka Server (8761)
│   ├── config-server/          ← Spring Cloud Config (8888)
│   ├── api-gateway/            ← Spring Cloud Gateway (8080)
│   ├── identity-service/       ← Auth: register, login, JWT (8081)
│   ├── merchant-service/       ← Onboarding, API keys (8082)
│   ├── payment-service/        ← Orders, payments, refunds (8083)
│   ├── routing-service/        ← ISO 8583, fraud, routing (8084)
│   ├── settlement-service/     ← Batch settlements (8085)
│   ├── webhook-service/        ← Event delivery (8086)
│   ├── notification-service/   ← Email/SMS (8087)
│   └── bank-simulator/         ← Mock bank (9000/9090)
├── frontend/                   ← React 18 + TypeScript 5
│   ├── merchant-portal/        ← Dashboard (3000)
│   └── hosted-checkout/        ← Payment page (3001)
├── infra/                      ← Docker, scripts, Postman, AWS
│   ├── docker/
│   ├── scripts/
│   ├── postman/
│   └── aws/
├── docs/                       ← Documentation (40+ files)
├── .github/workflows/          ← CI/CD pipelines
├── README.md
├── CONTRIBUTING.md
├── PROJECT_PROMPT.md
├── PROJECT_STRUCTURE.md
└── .gitignore
```

## File Count: ~384 files
