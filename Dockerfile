FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B clean verify
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/meridian-api-1.0.0.jar /app/app.jar
ENV MERIDIAN_BIND=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
