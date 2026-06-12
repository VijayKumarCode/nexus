# Stage 1: Build stage
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /app

# Copy pom.xml and cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build package
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Create a non-root system user and group for running the application securely
RUN addgroup -S spring && adduser -S spring -G spring

# Copy the built jar from the builder stage
COPY --from=builder /app/target/nexus-backend-0.0.1-SNAPSHOT.jar app.jar

# JVM Tuning options:
# - XX:+UseContainerSupport: Ensures the JVM respects container CPU/memory limits
# - XX:MaxRAMPercentage=75.0: Allocates up to 75% of container memory to the heap
# - XX:+ExitOnOutOfMemoryError: Exits JVM immediately on OOM so orchestrators can restart it
# - Djava.security.egd: Speeds up cryptographic operations (like UUID/OTP generation)
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

# Port exposed by the application
EXPOSE 8080

# Health check to monitor container health via the UserController /api/users/health endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/api/users/health || exit 1

# Switch to the non-root system user
USER spring:spring

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
