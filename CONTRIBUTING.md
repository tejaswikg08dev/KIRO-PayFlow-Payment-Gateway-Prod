# Contributing to PayFlow Payment Gateway

## Getting Started

1. Fork the repository
2. Clone your fork
3. Create a feature branch: `git checkout -b feature/your-feature`
4. Make your changes
5. Run tests: `cd backend && mvn clean verify`
6. Commit: `git commit -m "feat: description of change"`
7. Push: `git push origin feature/your-feature`
8. Open a Pull Request

## Development Setup

### Prerequisites
- Java 17+
- Maven 3.9+
- Node.js 20+
- Docker Desktop
- PostgreSQL client (DBeaver recommended)

### Start Infrastructure
```bash
cd infra/docker
docker compose up -d
```

### Build & Run
```bash
cd backend
mvn clean install -DskipTests
mvn spring-boot:run -pl service-registry
mvn spring-boot:run -pl config-server
# ... start services in order
```

## Code Style

- Java: Follow existing patterns (Lombok, MapStruct, Spring conventions)
- TypeScript: ESLint + Prettier (configured in .eslintrc.cjs)
- SQL: Uppercase keywords, snake_case for columns
- YAML: 2-space indent

## Commit Convention

Use conventional commits:
- `feat:` — new feature
- `fix:` — bug fix
- `docs:` — documentation only
- `refactor:` — code change that neither fixes a bug nor adds a feature
- `test:` — adding or fixing tests
- `chore:` — changes to build process or auxiliary tools

## Branch Naming

- `feature/description` — new features
- `fix/description` — bug fixes
- `docs/description` — documentation updates

## Testing

- Write unit tests for all service layer methods
- Write controller tests for all endpoints
- Aim for >80% code coverage on business logic
- Use `@DisplayName` for readable test names

## Pull Request Process

1. Ensure all tests pass
2. Update documentation if API changes
3. Add description of what changed and why
4. Link related issues
