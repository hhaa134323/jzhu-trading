# Backtest-service with Python3 for PYTHON_CODE strategy execution
# Extends the same base as manage.sh uses (maven:3.9.9-eclipse-temurin-21)
FROM maven:3.9.9-eclipse-temurin-21

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    && rm -rf /var/lib/apt/lists/*

# Create symlink so `python3` is also callable as `python`
RUN ln -s /usr/bin/python3 /usr/bin/python

# Copy Python daemon script for strategy execution
COPY backtest-service/src/main/resources/python/strategy_daemon.py /app/strategy_daemon.py

WORKDIR /workspace

# No ENTRYPOINT �?manage.sh controls the command line via docker run arguments.
# This keeps the image flexible for both service start and test execution.
# The command format used by manage.sh is:
#   mvn -f /workspace/backtest-service/pom.xml spring-boot:run -DskipTests



