# ===============================
# Base Dockerfile
# ===============================
FROM eclipse-temurin:21-jdk AS base

WORKDIR /app
COPY target/fileservice-service-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-jar","/app/app.jar"]
