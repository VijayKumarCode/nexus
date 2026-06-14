const CONFIG = {
    API_BASE_URL:
        window.location.hostname === "localhost"
            ? "http://localhost:8080"
            : "https://nexus-yxa3.onrender.com",
    WS_BASE_URL:
        window.location.hostname === "localhost"
            ? "ws://localhost:8080"
            : "wss://nexus-yxa3.onrender.com",
    FRONTEND_URL: window.location.origin,
    ENDPOINTS: {
        ACTIVATE: "/api/users/activate",
        RESEND_ACTIVATION: "/api/users/resend-activation",
        LOGIN: "/api/auth/login",
        REGISTER: "/api/auth/register",
        REFRESH: "/api/auth/refresh",
        LOGOUT: "/api/auth/logout",
        WS_GAME: "/ws/game"
    },
    TOKEN_KEY: "jwt_token",
    REFRESH_TOKEN_KEY: "refresh_token",
    COOLDOWN_SECONDS: 60
};