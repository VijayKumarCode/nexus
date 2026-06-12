#!/bin/bash
# Local development start script
# Set environment variables in your local environment or an untracked .env file.
# Do NOT commit production credentials to version control.

export DB_URL="${DEV_DB_URL:--jdbc:postgresql://localhost:5432/nexus_db}"
export DB_USERNAME="${DEV_DB_USERNAME:-postgres}"
export DB_PASSWORD="${DEV_DB_PASSWORD:-postgres}"
export RESEND_API_KEY="${DEV_RESEND_API_KEY:-}"
export MAIL_FROM="${DEV_MAIL_FROM:-onboarding@resend.dev}"
export APP_BASE="${DEV_APP_BASE:-http://localhost:8080}"

mvn spring-boot:run -Dspring-boot.run.profiles=dev
