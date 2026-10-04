FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

COPY pom.xml .

RUN mvn dependency:go-offline

COPY src ./src

RUN mvn clean package -DskipTests


FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /build/target/IUHEventMonitor-1.0-SNAPSHOT.jar app.jar

RUN mkdir -p /app/data

CMD ["java", "-jar", "/app/app.jar"]