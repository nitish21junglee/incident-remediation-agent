# syntax=docker/dockerfile:1

FROM node:22-alpine AS dashboard-build
WORKDIR /dashboard
COPY dashboard/package.json dashboard/package-lock.json ./
RUN npm ci
COPY dashboard/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
COPY --from=dashboard-build /dashboard/dist/ src/main/resources/static/
# Test sources do not currently compile against AgentProperties, so skip test compilation
# outright rather than only test execution.
RUN ./mvnw -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/incident_remediation_agent-*.jar app.jar
# Tuned for a 512 MB container: cap the heap below the limit and use the low-overhead collector.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
