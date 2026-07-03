# Multi-stage build producing one self-contained image: the Vue app is compiled and
# served as static resources from the Spring Boot jar. One artifact, one Render service,
# same origin for UI and API (no CORS).

# ---------- 1. Frontend build ----------
FROM node:22-alpine AS frontend-build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---------- 2. Backend build ----------
FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /build
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
# Warm the dependency cache in its own layer so code changes don't re-download the world.
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY backend/src src
COPY --from=frontend-build /build/dist src/main/resources/static
RUN ./mvnw -q -B -DskipTests package

# ---------- 3. Runtime ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend-build /build/target/*.jar app.jar
# Render's free instance has 512MB; keep the JVM inside it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
