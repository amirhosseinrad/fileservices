# Multi-stage build to keep the runtime image lean while still compiling with Maven.
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /workspace

# Cache dependencies by copying the Maven descriptor first.
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw
RUN chmod +x mvnw
RUN ./mvnw -B dependency:go-offline

# Copy the remaining project files and build the Spring Boot fat JAR.
COPY src src
RUN ./mvnw -B -DskipTests clean package

# Runtime image: Java + PostgreSQL server.
FROM eclipse-temurin:21-jre
WORKDIR /app

# Install PostgreSQL server and utilities.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends postgresql postgresql-contrib \
    && rm -rf /var/lib/apt/lists/*

# Default database connection settings mirrored from application.properties
ENV DB_HOST=localhost \
    DB_PORT=5432 \
    DB_NAME=fileservices \
    DB_USER=postgres \
    DB_PASSWORD=Amir@123456 \
    DB_SCHEMA=public

# PostgreSQL expects a couple of directories to exist when running as root in a container.
RUN mkdir -p /var/run/postgresql && chown postgres:postgres /var/run/postgresql

# Copy the built application and the bootstrap script.
COPY --from=build /workspace/target/*.jar /app/app.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8002 5432

ENTRYPOINT ["/app/entrypoint.sh"]
