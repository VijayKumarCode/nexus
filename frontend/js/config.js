/* ═══════════════════════════════════════════
   NEXUS ENGINE CONFIGURATION LAYER — v5.0
   Architectural Security & Pipeline Isolation
   Immutability Guarantee: Deep Frozen
   ═══════════════════════════════════════════ */

'use strict';

const NexusConfigEngine = (() => {
    const hostname = window.location.hostname;
    const origin = window.location.origin;

    // 1. Dynamic Environment Detection Matrix
    const isLocalhost = hostname === "localhost" || hostname === "127.0.0.1" || hostname === "[::1]";
    const isDevelopmentIP = /^192\.168\.\d+\.\d+$/.test(hostname) || /^10\.\d+\.\d+\.\d+$/.test(hostname);

    // 2. Base URL Derivation Strategy
    let apiBaseUrl;
    if (isLocalhost) {
        apiBaseUrl = "http://localhost:8080";
    } else if (isDevelopmentIP) {
        // Automatically allows testing on physical mobile devices connected to local LAN setups
        apiBaseUrl = `http://${hostname}:8080`;
    } else {
        // Production fallback — points to your specialized container service
        apiBaseUrl = "https://nexus-yxa3.onrender.com";
    }

    // 3. SockJS Handshake Endpoint Alignment
    // SockJS handles transport upgrading internally. The client initialization string
    // MUST pass standard HTTP/S schemas to enable initial handshake negotiation.
    const wsHandshakeUrl = apiBaseUrl;

    const rawConfig = {
        ENV: isLocalhost ? "DEVELOPMENT" : "PRODUCTION",
        API_BASE_URL: apiBaseUrl,
        WS_HANDSHAKE_URL: wsHandshakeUrl, // Explicitly decoupled for SockJS initialization
        FRONTEND_URL: origin,

        ENDPOINTS: {
            ACTIVATE: "/api/users/activate",
            RESEND_ACTIVATION: "/api/users/resend-activation",
            LOGIN: "/api/users/login",
            REGISTER: "/api/users/register",
            REFRESH: "/api/users/refresh",
            LOGOUT: "/api/users/logout",
            WS_GAME: "/game-websocket" // Perfectly matches Spring boot endpoint mapper
        },

        // 4. Isolated Namespacing Pattern to prevent token collisions
        STORAGE: {
            TOKEN_KEY: "nexus_player_jwt_secure_token",
            REFRESH_TOKEN_KEY: "nexus_player_refresh_secure_token",
            USER_DATA_KEY: "nexus_player_profile_cache"
        },

        TIMERS: {
            COOLDOWN_SECONDS: 60,
            HEARTBEAT_MS: 10000,
            API_TIMEOUT_MS: 8000
        }
    };

    // 5. Deep Freeze Optimization Pattern for Immutability Guard
    const deepFreeze = (obj) => {
        Object.keys(obj).forEach(name => {
            const prop = obj[name];
            if (typeof prop === 'object' && prop !== null) {
                deepFreeze(prop);
            }
        });
        return Object.freeze(obj);
    };

    return deepFreeze(rawConfig);
})();

// Export clean global invariant instance
window.CONFIG = NexusConfigEngine;