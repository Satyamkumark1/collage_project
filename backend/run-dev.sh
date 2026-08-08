#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
set -a
source .env
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
