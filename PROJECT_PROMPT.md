# PayFlow Payment Gateway — Project Prompt

This file contains the AI prompt used to generate this project. See the full prompt in the provided PROJECT-PROMPT.md document.

## Summary

PayFlow is a production-ready payment gateway platform built with:
- 11 Java Spring Boot microservices
- ISO 8583 protocol for bank communication
- AI-powered fraud detection
- Smart payment routing (multi-armed bandit)
- React/TypeScript merchant dashboard and checkout page

## Generation Methodology

1. Phase 0: Project Overview & Environment Setup
2. Phase 1: System Design (with Design Decision boxes)
3. Phase 2: High-Level Design (with failure modes)
4. Phase 3: Low-Level Design (all POMs, entities, configs)
5. Phase 4: Coding (15 hands-on parts)
6. Phase 5: Testing
7. Phase 6: Docker & Containerization
8. Phase 7: CI/CD Pipeline
9. Phase 8: AWS Deployment
10. Phase 9: Monitoring & Observability

## Key Design Decisions

- Microservices over monolith (independent scaling)
- PostgreSQL for ACID payments, DynamoDB for events
- Kafka for event streaming, SQS as AWS fallback
- ISO 8583 over JSON for bank communication (industry standard)
- JWT for users, API keys for merchants
- Idempotency via Redis SET NX for payment safety
- Circuit breaker (Resilience4j) for bank fault tolerance
- Exponential backoff retry for webhook delivery
