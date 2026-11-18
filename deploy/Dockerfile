FROM node:22.23.1-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-alpine AS backend
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sh mvnw --batch-mode dependency:go-offline
COPY src/ src/
RUN sh mvnw --batch-mode package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 10001 -S tapebeta && adduser -u 10001 -S -G tapebeta tapebeta
WORKDIR /opt/tape
COPY --from=backend /build/target/tape-1.0.0.jar app.jar
COPY --from=frontend /build/frontend/dist/ frontend/dist/
COPY docs/*-runtime-grants.sql docs/
USER tapebeta
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-XX:+UseSerialGC", "-Duser.timezone=UTC", "-jar", "app.jar"]
