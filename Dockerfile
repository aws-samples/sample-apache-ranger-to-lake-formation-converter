# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY conf ./conf
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/ranger-lakeformation-plugin-*-jar-with-dependencies.jar app.jar
COPY conf/server-config.yaml /app/config.yaml

STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD pgrep -f "java.*app.jar" || exit 1

ENTRYPOINT ["java", "-jar", "app.jar", "/app/config.yaml"]
