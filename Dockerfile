FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN addgroup --system stockflow && adduser --system --ingroup stockflow --home /opt/stockflow stockflow \
    && mkdir -p /opt/stockflow /var/lib/stockflow/documents \
    && chown -R stockflow:stockflow /opt/stockflow /var/lib/stockflow
COPY --from=build /workspace/target/*.jar /opt/stockflow/app.jar
COPY docker/outbox-worker.sh /opt/stockflow/outbox-worker.sh
RUN chmod 0555 /opt/stockflow/outbox-worker.sh
USER stockflow
WORKDIR /opt/stockflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/stockflow/app.jar"]
