# 🏗️ Phase 4 Part 1: Parent POM & Maven Multi-Module Setup

> **"A well-structured Maven build is the foundation of a maintainable microservices project."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation (Part 1 of 15) |
| **Previous** | [phase3-low-level-design.md](./phase3-low-level-design.md) |
| **Next** | [phase4-part02-common-lib.md](./phase4-part02-common-lib.md) |
| **Author** | Tejaswi |
| **Created** | 2024 |
| **Status** | Complete |

---

## 📖 Table of Contents

1. [Maven Multi-Module Concepts](#maven-multi-module-concepts)
2. [Parent POM Line-by-Line](#parent-pom-line-by-line)
3. [Module Structure](#module-structure)
4. [Dependency Management Strategy](#dependency-management-strategy)
5. [Build Commands](#build-commands)
6. [What You Learned](#what-you-learned)
7. [Document Index](#document-index)

---

## 📦 Maven Multi-Module Concepts

### Why Multi-Module?

```
┌─────────────────────────────────────────────────────────────────┐
│            MONOLITH POM vs MULTI-MODULE POM                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Single POM (Bad for microservices):                             │
│  ┌─────────────────────────────────┐                            │
│  │  pom.xml                        │                            │
│  │  ALL dependencies in one file   │  ← Huge, unmanageable     │
│  │  ALL code in one project        │  ← Long build times       │
│  │  Can't deploy independently     │  ← All or nothing         │
│  └─────────────────────────────────┘                            │
│                                                                   │
│  Multi-Module (PayFlow approach):                                │
│  ┌─────────────────────────────────┐                            │
│  │  parent/pom.xml (packaging: pom)│  ← Manages versions only  │
│  │    ├── common-lib/pom.xml       │  ← Shared code            │
│  │    ├── identity-service/pom.xml │  ← Independent service    │
│  │    ├── payment-service/pom.xml  │  ← Independent service    │
│  │    └── ...                      │                            │
│  └─────────────────────────────────┘                            │
│                                                                   │
│  Benefits:                                                        │
│  ✓ Version management in ONE place                              │
│  ✓ Each module builds independently                             │
│  ✓ Shared code via common-lib                                   │
│  ✓ Consistent plugin configuration                              │
│  ✓ Single `mvn clean install` builds everything                 │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

### Inheritance vs Aggregation

```
INHERITANCE (parent element):
  Child POM inherits versions, plugins, properties from parent.
  Like Java class inheritance.

  identity-service/pom.xml:
    <parent>
      <groupId>com.payflow</groupId>
      <artifactId>payflow-payment-gateway</artifactId>
      <version>1.0.0-SNAPSHOT</version>
    </parent>

AGGREGATION (modules element):
  Parent POM lists all child modules.
  Running `mvn install` on parent builds ALL modules.

  parent/pom.xml:
    <modules>
      <module>common-lib</module>
      <module>identity-service</module>
      ...
    </modules>

PayFlow uses BOTH: parent is both the aggregator AND the inherited parent.
```

---

## 📝 Parent POM Line-by-Line

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- File: backend/pom.xml -->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- ═══════════════════════════════════════════════════════════════
         SPRING BOOT PARENT
         This makes our POM inherit from Spring Boot's parent POM,
         which provides:
         - Default dependency versions (Jackson, Hibernate, etc.)
         - Plugin configurations (spring-boot-maven-plugin)
         - Default properties (Java version, encoding)
         ═══════════════════════════════════════════════════════════════ -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/> <!-- Lookup from Maven repository, not filesystem -->
    </parent>

    <!-- ═══════════════════════════════════════════════════════════════
         PROJECT COORDINATES (GAV)
         GroupId: Company/organization identifier
         ArtifactId: Project name (unique within group)
         Version: SNAPSHOT = under development, not released
         Packaging: "pom" means this is a parent/aggregator only
         ═══════════════════════════════════════════════════════════════ -->
    <groupId>com.payflow</groupId>
    <artifactId>payflow-payment-gateway</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>   <!-- ← KEY: This is not a JAR, it's a parent -->
    <name>PayFlow Payment Gateway</name>
    <description>Production-ready payment gateway with 11 microservices</description>

    <!-- ═══════════════════════════════════════════════════════════════
         MODULE DECLARATION
         Lists all child modules. Maven will build them in dependency
         order (common-lib first since others depend on it).
         ═══════════════════════════════════════════════════════════════ -->
    <modules>
        <module>common-lib</module>           <!-- Must be first (no dependencies) -->
        <module>service-registry</module>     <!-- Infrastructure -->
        <module>config-server</module>        <!-- Infrastructure -->
        <module>api-gateway</module>          <!-- Edge service -->
        <module>identity-service</module>     <!-- Business service -->
        <module>merchant-service</module>     <!-- Business service -->
        <module>payment-service</module>      <!-- Business service -->
        <module>routing-service</module>      <!-- Business service -->
        <module>settlement-service</module>   <!-- Business service -->
        <module>webhook-service</module>      <!-- Business service -->
        <module>notification-service</module> <!-- Business service -->
        <module>bank-simulator</module>       <!-- Testing tool -->
    </modules>

    <!-- ═══════════════════════════════════════════════════════════════
         CENTRALIZED VERSION PROPERTIES
         All dependency versions defined HERE, used by all children.
         Change version in ONE place → all modules update.
         ═══════════════════════════════════════════════════════════════ -->
    <properties>
        <java.version>17</java.version>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

        <!-- Spring Cloud BOM — manages versions for Eureka, Config, Gateway -->
        <spring-cloud.version>2023.0.1</spring-cloud.version>

        <!-- Library versions used across multiple modules -->
        <jjwt.version>0.12.5</jjwt.version>
        <mapstruct.version>1.5.5.Final</mapstruct.version>
        <lombok.version>1.18.32</lombok.version>
        <lombok-mapstruct-binding.version>0.2.0</lombok-mapstruct-binding.version>
        <netty.version>4.1.109.Final</netty.version>
        <resilience4j.version>2.2.0</resilience4j.version>
        <springdoc.version>2.5.0</springdoc.version>
        <aws-sdk.version>2.25.40</aws-sdk.version>
        <testcontainers.version>1.19.7</testcontainers.version>
        <jacoco.version>0.8.12</jacoco.version>
    </properties>

    <!-- ═══════════════════════════════════════════════════════════════
         DEPENDENCY MANAGEMENT
         This section does NOT add dependencies to children.
         It only CONTROLS VERSIONS when children declare the dependency.
         
         Think of it as: "IF a child needs spring-cloud, use THIS version"
         ═══════════════════════════════════════════════════════════════ -->
    <dependencyManagement>
        <dependencies>
            <!-- Spring Cloud BOM: imports ALL Spring Cloud dependency versions -->
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>  <!-- ← BOM import pattern -->
            </dependency>

            <!-- PayFlow Common Library: our shared DTOs, events, exceptions -->
            <dependency>
                <groupId>com.payflow</groupId>
                <artifactId>common-lib</artifactId>
                <version>${project.version}</version>
            </dependency>

            <!-- JWT libraries (3 artifacts work together) -->
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-api</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-impl</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-jackson</artifactId>
                <version>${jjwt.version}</version>
            </dependency>

            <!-- MapStruct: compile-time DTO mapping (no reflection) -->
            <dependency>
                <groupId>org.mapstruct</groupId>
                <artifactId>mapstruct</artifactId>
                <version>${mapstruct.version}</version>
            </dependency>

            <!-- Netty: async TCP framework for ISO 8583 bank communication -->
            <dependency>
                <groupId>io.netty</groupId>
                <artifactId>netty-all</artifactId>
                <version>${netty.version}</version>
            </dependency>

            <!-- Resilience4j: circuit breaker, retry, rate limiter -->
            <dependency>
                <groupId>io.github.resilience4j</groupId>
                <artifactId>resilience4j-spring-boot3</artifactId>
                <version>${resilience4j.version}</version>
            </dependency>

            <!-- SpringDoc: auto-generates Swagger UI from annotations -->
            <dependency>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                <version>${springdoc.version}</version>
            </dependency>
            <dependency>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
                <version>${springdoc.version}</version>
            </dependency>

            <!-- AWS SDK v2 BOM: for DynamoDB in webhook service -->
            <dependency>
                <groupId>software.amazon.awssdk</groupId>
                <artifactId>bom</artifactId>
                <version>${aws-sdk.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>

            <!-- Testcontainers BOM: for integration testing with Docker -->
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>${testcontainers.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <!-- ═══════════════════════════════════════════════════════════════
         GLOBAL DEPENDENCIES
         These are added to ALL child modules automatically.
         Only put truly universal dependencies here.
         ═══════════════════════════════════════════════════════════════ -->
    <dependencies>
        <!-- Lombok: reduces boilerplate (getters, setters, builders) -->
        <!-- scope=provided: only needed at compile time, not in JAR -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>${lombok.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Spring Boot Test: JUnit 5 + Mockito + AssertJ + more -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <!-- ═══════════════════════════════════════════════════════════════
         BUILD PLUGIN MANAGEMENT
         Configures plugins that children can activate.
         Children get these configs by declaring the plugin (no version needed).
         ═══════════════════════════════════════════════════════════════ -->
    <build>
        <pluginManagement>
            <plugins>
                <!-- Compiler plugin with annotation processors -->
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                        <source>${java.version}</source>
                        <target>${java.version}</target>
                        <annotationProcessorPaths>
                            <!-- Order matters! Lombok must run before MapStruct -->
                            <path>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok</artifactId>
                                <version>${lombok.version}</version>
                            </path>
                            <path>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok-mapstruct-binding</artifactId>
                                <version>${lombok-mapstruct-binding.version}</version>
                            </path>
                            <path>
                                <groupId>org.mapstruct</groupId>
                                <artifactId>mapstruct-processor</artifactId>
                                <version>${mapstruct.version}</version>
                            </path>
                        </annotationProcessorPaths>
                    </configuration>
                </plugin>

                <!-- JaCoCo: code coverage reports -->
                <plugin>
                    <groupId>org.jacoco</groupId>
                    <artifactId>jacoco-maven-plugin</artifactId>
                    <version>${jacoco.version}</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

---

## 📁 Module Structure

### How Each Module Inherits

```
┌───────────────────────────────────────────────────────────────────┐
│                   MAVEN INHERITANCE CHAIN                           │
├───────────────────────────────────────────────────────────────────┤
│                                                                     │
│  spring-boot-starter-parent (Maven Central)                        │
│       │                                                             │
│       │ provides: Spring Boot defaults, plugin versions             │
│       ▼                                                             │
│  payflow-payment-gateway (our parent POM)                          │
│       │                                                             │
│       │ provides: Library versions, Lombok, common plugins          │
│       ▼                                                             │
│  identity-service (child POM)                                      │
│       │                                                             │
│       │ declares: spring-boot-starter-web (no version needed!)     │
│       │ inherits: java 17, lombok, test dependencies               │
│       ▼                                                             │
│  Effective POM = merged parent + child                             │
│                                                                     │
└───────────────────────────────────────────────────────────────────┘
```

### Child POM Example (Identity Service)

```xml
<!-- File: backend/identity-service/pom.xml -->
<project>
    <parent>
        <groupId>com.payflow</groupId>
        <artifactId>payflow-payment-gateway</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>identity-service</artifactId>
    <name>Identity Service</name>

    <dependencies>
        <!-- No version specified! Managed by parent -->
        <dependency>
            <groupId>com.payflow</groupId>
            <artifactId>common-lib</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
        </dependency>
        <!-- ... more dependencies ... -->
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 🔨 Build Commands

```bash
# Build everything (from backend/ directory)
mvn clean install

# Build specific module only
mvn clean install -pl identity-service -am
# -pl = project list (specific module)
# -am = also make (build dependencies like common-lib)

# Skip tests (faster builds during development)
mvn clean package -DskipTests

# Run specific service
mvn spring-boot:run -pl identity-service

# View effective POM (merged parent + child)
mvn help:effective-pom -pl identity-service

# Dependency tree (debug version conflicts)
mvn dependency:tree -pl payment-service

# Check for outdated dependencies
mvn versions:display-dependency-updates
```

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|--------------|
| 1 | Multi-Module Maven | Parent POM manages versions, children inherit |
| 2 | packaging: pom | Parent is aggregator, not a JAR |
| 3 | dependencyManagement | Controls versions WITHOUT adding dependencies |
| 4 | dependencies section | Actually adds to all children (use sparingly) |
| 5 | BOM imports | Import another POM's dependency management |
| 6 | pluginManagement | Pre-configures plugins for children to use |
| 7 | Module order | common-lib first, services after |
| 8 | Annotation processors | Lombok → MapStruct binding → MapStruct (order matters) |
| 9 | Build commands | -pl for specific module, -am for dependencies |
| 10 | Version properties | Single source of truth for all versions |

---

## 📚 Document Index

| # | Document | Description |
|---|----------|-------------|
| 4.01 | [phase4-part01-parent-pom-and-maven-setup.md](./phase4-part01-parent-pom-and-maven-setup.md) | **This document** |
| 4.02 | [phase4-part02-common-lib.md](./phase4-part02-common-lib.md) | Common library |
| 4.03 | [phase4-part03-service-registry.md](./phase4-part03-service-registry.md) | Eureka |
| 4.04 | [phase4-part04-config-server.md](./phase4-part04-config-server.md) | Config Server |
| 4.05 | [phase4-part05-api-gateway.md](./phase4-part05-api-gateway.md) | API Gateway |

---

## 🚀 Next Steps

1. Create the `common-lib` module with shared DTOs and exceptions
2. Set up service registry (Eureka) for service discovery
3. Configure config server for centralized configuration

---

*"Maven is like a good butler — it manages the household so you can focus on the business."*
