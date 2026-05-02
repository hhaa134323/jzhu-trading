Docker Maven setup for this project

Prerequisites
- Docker Desktop is running
- Internet access to pull Docker images

One-time image pull
- docker pull maven:3.9.9-eclipse-temurin-21

Windows PowerShell usage
- .\mvnw-docker.ps1 -v
- .\mvnw-docker.ps1 -pl market-data-service -am clean compile -DskipTests

Windows CMD usage
- mvnw-docker.cmd -v
- mvnw-docker.cmd -pl market-data-service -am clean compile -DskipTests

How it works
- Mounts project directory to /workspace in container
- Mounts local Maven cache from %USERPROFILE%\.m2 to /root/.m2
- Runs Maven 3.9.9 with Java 21 from official image maven:3.9.9-eclipse-temurin-21
