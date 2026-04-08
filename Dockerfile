# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY conf ./conf
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/ranger-lakeformation-plugin-*-jar-with-dependencies.jar app.jar
COPY conf/server-config.yaml /app/config.yaml
COPY conf/ranger-lakeformation-audit.xml /app/ranger-lakeformation-audit.xml
COPY conf/ranger-lakeformation-security.xml /app/ranger-lakeformation-security.xml
COPY conf/ranger-lakeformation-policymgr-ssl.xml /app/ranger-lakeformation-policymgr-ssl.xml

STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD pgrep -f "java.*app.jar" || exit 1

ENTRYPOINT ["java", "-jar", "app.jar", "/app/config.yaml"]
