# Nexus Multiplayer Arena
Real-time multiplayer Tic-Tac-Toe platform. Live at [nexusgame.space](https://nexusgame.space).

## Tech Stack
| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 3, Spring Data JPA |
| Real-time | WebSocket (STOMP / SockJS) |
| Database | PostgreSQL (Neon) |
| Email | Resend HTTP API |
| Deployment | Oracle Cloud VM + GitHub Actions CI/CD |
| Frontend | Vanilla JS, deployed on Vercel |

## Architecture
- Stateful game engine using ConcurrentHashMap with DB-backed recovery on restart
- STOMP WebSocket for challenge flow, toss, and live move broadcasting
- Bcrypt password hashing, email activation, OTP-based account recovery
- GitHub Actions: builds JAR → SCP to Oracle VM → restarts systemd service

## Local Setup
```bash
git clone https://github.com/VijayKumarCode/nexus
cd nexus/backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -DDB_URL=... -DRESEND_API_KEY=...
```
Frontend: open `nexus/frontend/index.html` in browser or use Live Server.
