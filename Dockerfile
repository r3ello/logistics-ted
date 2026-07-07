# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Copy pom.xml first for layer caching
COPY pom.xml .

# Download dependencies (resolve instead of go-offline for better BOM support)
RUN mvn dependency:resolve -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests -B

# Stage 2: Run
FROM eclipse-temurin:21-jre-alpine

# wget already ships with alpine; install nothing else.

# Create non-root user
RUN addgroup -S spring && adduser -S spring -G spring

WORKDIR /app

COPY --from=builder /app/target/logistics-ted-0.0.1-SNAPSHOT.jar app.jar

RUN chown -R spring:spring /app

USER spring:spring

# Force the app to listen on 8080 so it matches EXPOSE and the healthcheck below,
# regardless of the SERVER_PORT default in application.yaml.
ENV SERVER_PORT=8080

# JVM container ergonomics: cap heap at 75% of cgroup memory, lower idle CPU usage.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

# Emit one ECS-JSON log object per line to stdout (Spring Boot 3.4+ structured logging) so a log
# shipper can ingest it. Local `mvnw spring-boot:run` leaves this unset and stays human-readable.
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs

EXPOSE 8080

# Spring Boot + Flyway can take 30-60s on a cold container; give it 90s grace before
# the healthcheck starts counting failures.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
