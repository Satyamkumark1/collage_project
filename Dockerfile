# Backend-only image — frontend deploys separately on Vercel. Frontend and backend are
# different origins in prod; see docs/DECISIONS.md for the CORS/cookie implications.

FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /app
COPY backend/.mvn .mvn
COPY backend/mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY backend/src ./src
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend-build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
