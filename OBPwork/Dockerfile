FROM maven:3.9-eclipse-temurin-21 AS build

ARG MODULE
WORKDIR /workspace

COPY pom.xml ./
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY user-service/pom.xml user-service/pom.xml
COPY restaurant-service/pom.xml restaurant-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml

RUN mvn -B -pl ${MODULE} -am dependency:go-offline

COPY api-gateway/src api-gateway/src
COPY user-service/src user-service/src
COPY restaurant-service/src restaurant-service/src
COPY order-service/src order-service/src

RUN mvn -B -pl ${MODULE} -am clean package -DskipTests \
    && cp "${MODULE}/target/${MODULE}-1.0.0.jar" /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
