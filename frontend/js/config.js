const CONFIG = {
    API_BASE_URL:
        window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1"
            ? "http://localhost:8080"
            : "https://nexus-yxa3.onrender.com",
    WS_BASE_URL:
        window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1"
            ? "ws://localhost:8080"
            : "wss://nexus-yxa3.onrender.com",
    FRONTEND_URL: window.location.origin,
    ENDPOINTS: {
        ACTIVATE: "/api/users/activate",
        RESEND_ACTIVATION: "/api/users/resend-activation",
        LOGIN: "/api/users/login",       // Fixed: changed from /api/auth/login
        REGISTER: "/api/users/register", // Fixed: changed from /api/auth/register
        REFRESH: "/api/users/refresh",   // Update if your backend uses a different path
        LOGOUT: "/api/users/logout",     // Update if your backend uses a different path
        WS_GAME: "/game-websocket"       // Fixed: changed from /ws/game to match Spring WebSocket endpoint
    },
    TOKEN_KEY: "jwt_token",
    REFRESH_TOKEN_KEY: "refresh_token",
    COOLDOWN_SECONDS: 60
};