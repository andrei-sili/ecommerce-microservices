# syntax=docker/dockerfile:1

# --- Build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Cache dependencies first: copy the wrapper + pom, resolve offline deps.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

# Build the application (skip tests in the image; tests run in CI).
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q clean package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user.
RUN groupadd --system app && useradd --system --gid app --no-create-home app

COPY --from=build /app/target/*.jar app.jar
RUN chown app:app app.jar
USER app

EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
