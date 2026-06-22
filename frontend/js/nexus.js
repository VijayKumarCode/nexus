/* ═══════════════════════════════════════════
   NEXUS MULTIPLAYER ARENA — v4.0 (Architect Refactored)
   Game Logic & WebSocket Client
   © 2026 Vijay Kumar. All rights reserved.
═══════════════════════════════════════════ */

'use strict';

/* ══════════════════════════════════
   CONFIG
══════════════════════════════════ */
const BACKEND_URL = (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1')
    ? 'http://localhost:8080'
    : 'https://nexus-yxa3.onrender.com';

const API_BASE = `${BACKEND_URL}/api/users`;
const RECOVERY_BASE = `${BACKEND_URL}/api/recovery`;
const WS_ENDPOINT = `${BACKEND_URL}/game-websocket`;

/* ══════════════════════════════════
   STATE
══════════════════════════════════ */
const STATE = {
    auth: {
        currentUser: '',
        tokenKey: 'nexus_token',
        userKey: 'nexus_user',
        heartbeatIntervalId: null,
        usernameCheckTimeoutId: null
    },
    websocket: {
        stompClient: null,
        isConnected: false,
        reconnectAttempt: 0,
        maxReconnectDelay: 30000,
        baseReconnectDelay: 3000,
        reconnectTimeoutId: null,
        subscriptions: {
            lobby: null,
            challenge: null,
            room: null
        }
    },
    lobby: {
        lobbyIntervalId: null,
        leaderboardIntervalId: null,
        challengePending: false
    },
    game: {
        machineState: 'IDLE',
        opponentUser: null,
        currentRoomId: '',
        isMyTurn: false,
        isGameOver: false,
        mySymbol: '',
        tossSubmitted: false,
        tossGameStartHandled: false,
        boardLocked: false
    },
    recovery: {
        pendingEmail: '',
        recoveryMode: ''
    },
    ui: {
        selectedStar: 0,
        activeModal: null,
        lastFocusedElement: null
    }
};

/* ══════════════════════════════════
   DOM CACHE
══════════════════════════════════ */
const DomCache = {
    elements: {},
    init() {
        const ids = [
            'status-indicator', 'toast-container', 'lobby-greeting', 'lobby-avatar-el',
            'room-p1', 'room-p2', 'feedback-text', 'feedback-category', 'feedback-modal',
            'reg-username', 'username-feedback', 'reg-fullname', 'reg-email', 'reg-password',
            'login-username', 'login-password', 'recovery-title', 'btn-recovery-submit',
            'password-reset-fields', 'recovery-step-1', 'recovery-step-2', 'recovery-email',
            'recovery-otp', 'recovery-new-password', 'player-search', 'online-users-list',
            'leaderboard-list', 'waiting-modal', 'waiting-text', 'challenge-modal',
            'challenge-text', 'btn-toss', 'toss-modal', 'toss-modal-card', 'toss-result-title',
            'toss-result-desc', 'toss-winner-section', 'toss-loser-section', 'toss-waiting-text',
            'game-over-modal', 'go-title', 'go-icon', 'go-desc', 'game-container'
        ];
        ids.forEach(id => {
            this.elements[id] = document.getElementById(id);
        });
        this.elements.screens = document.querySelectorAll('.screen');
        this.elements.starButtons = document.querySelectorAll('.star-btn');
        this.elements.tossChoiceBtns = document.querySelectorAll('.toss-choice-btn');
        this.elements.cells = document.getElementsByClassName('cell');
        this.elements.registerBtn = document.querySelector('#register-screen .nx-btn-primary');
        this.elements.loginBtn = document.querySelector('#login-screen .nx-btn-success');
    },
    get(id) {
        return this.elements[id];
    }
};

/* ══════════════════════════════════
   UTILITIES
══════════════════════════════════ */
const Logger = {
    info(msg, ...args) { console.log(`[INFO] [${new Date().toISOString()}] ${msg}`, ...args); },
    warn(msg, ...args) { console.warn(`[WARN] [${new Date().toISOString()}] ${msg}`, ...args); },
    error(msg, ...args) { console.error(`[ERROR] [${new Date().toISOString()}] ${msg}`, ...args); }
};

const StorageManager = {
    getToken() { return localStorage.getItem(STATE.auth.tokenKey); },
    setToken(val) { localStorage.setItem(STATE.auth.tokenKey, val); },
    removeToken() { localStorage.removeItem(STATE.auth.tokenKey); },
    getUser() { return localStorage.getItem(STATE.auth.userKey); },
    setUser(val) { localStorage.setItem(STATE.auth.userKey, val); },
    removeUser() { localStorage.removeItem(STATE.auth.userKey); },
    clearAll() { this.removeToken(); this.removeUser(); }
};

const VALIDATORS = {
    email: (v) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v),
    username: (v) => /^[a-zA-Z0-9_]+$/.test(v),
    otp: (v) => /^\d{6}$/.test(v)
};

const ApiManager = {
    async request(url, options = {}, retries = 1, timeoutMs = 8000) {
        const token = StorageManager.getToken();
        options.headers = options.headers || {};
        if (token) {
            options.headers['Authorization'] = `Bearer ${token}`;
        }

        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
        options.signal = controller.signal;

        try {
            const response = await fetch(url, options);
            clearTimeout(timeoutId);
            if (!response.ok) {
                let errorData = null;
                try { errorData = await response.json(); } catch (e) {}
                throw { status: response.status, data: errorData };
            }
            const contentType = response.headers.get('content-type');
            if (contentType && contentType.includes('application/json')) {
                return await response.json();
            }
            return await response.text();
        } catch (error) {
            clearTimeout(timeoutId);
            if (retries > 0 && (error.name === 'AbortError' || error.message === 'Failed to fetch')) {
                Logger.warn(`Retrying API call to: ${url}`);
                return this.request(url, options, retries - 1, timeoutMs);
            }
            throw error;
        }
    }
};

const UIManager = {
    showScreen(screenId) {
        DomCache.get('screens').forEach(el => { el.style.display = 'none'; });
        const target = document.getElementById(screenId);
        if (target) {
            target.style.display = 'block';
            target.style.animation = 'none';
            requestAnimationFrame(() => {
                target.style.animation = 'fadeUp 0.4s ease forwards';
            });
        }
    },

    setStatus(text, type = 'info') {
        const el = DomCache.get('status-indicator');
        if (el) {
            el.textContent = text;
            el.className = `status-bar status-${type}`;
            el.setAttribute('aria-live', 'polite');
        }
    },

    showToast(message, type = 'info') {
        const container = DomCache.get('toast-container');
        if (!container) return;
        const toast = document.createElement('div');
        toast.className = `toast-item toast-${type}`;
        toast.textContent = message;
        toast.setAttribute('role', 'alert');
        toast.setAttribute('aria-live', 'assertive');
        container.appendChild(toast);
        requestAnimationFrame(() => { toast.style.opacity = '1'; });
        setTimeout(() => {
            toast.style.opacity = '0';
            setTimeout(() => { if (toast.parentNode) container.removeChild(toast); }, 300);
        }, 3000);
    },

    showModal(modalId) {
        const modal = DomCache.get(modalId);
        if (!modal) return;
        if (STATE.ui.activeModal) {
            this.closeModal(STATE.ui.activeModal.id);
        }
        STATE.ui.lastFocusedElement = document.activeElement;
        modal.style.display = 'flex';
        STATE.ui.activeModal = modal;
        const focusable = modal.querySelectorAll('button, input, select, textarea, [tabindex="0"]');
        if (focusable.length > 0) focusable[0].focus();
    },

    closeModal(modalId) {
        const modal = DomCache.get(modalId);
        if (!modal) return;
        modal.style.display = 'none';
        if (STATE.ui.activeModal === modal) {
            STATE.ui.activeModal = null;
        }
        if (STATE.ui.lastFocusedElement && typeof STATE.ui.lastFocusedElement.focus === 'function') {
            STATE.ui.lastFocusedElement.focus();
            STATE.ui.lastFocusedElement = null;
        }
    },

    updateLobbyGreeting() {
        const greeting = DomCache.get('lobby-greeting');
        if (greeting) greeting.textContent = STATE.auth.currentUser;
        const av = DomCache.get('lobby-avatar-el');
        if (av && STATE.auth.currentUser) {
            av.textContent = STATE.auth.currentUser.slice(0, 2).toUpperCase();
        }
    },

    setRoomDisplay(p1, p2) {
        const r1 = DomCache.get('room-p1');
        const r2 = DomCache.get('room-p2');
        if (r1) r1.textContent = p1;
        if (r2) r2.textContent = p2;
    },

    sanitizeText(str) {
        if (!str) return '';
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    },

    safeJsonParse(str) {
        try { return JSON.parse(str); }
        catch (e) { Logger.error('JSON Parse Error', e); return null; }
    },

    rateStar(n) {
        STATE.ui.selectedStar = n;
        DomCache.get('starButtons').forEach((btn, i) => {
            btn.classList.toggle('active', i < n);
        });
    },

    submitFeedback() {
        const text = DomCache.get('feedback-text').value.trim();
        const category = DomCache.get('feedback-category').value;
        if (!text) {
            this.showToast('Please write your feedback before sending.', 'warning');
            return;
        }
        Logger.info('Feedback:', { rating: STATE.ui.selectedStar, category, text, user: STATE.auth.currentUser });
        this.showToast('Thank you! Your feedback has been received.', 'success');
        this.closeModal('feedback-modal');
        DomCache.get('feedback-text').value = '';
        this.rateStar(0);
    },

    setupGlobalListeners() {
        window.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && STATE.ui.activeModal) {
                if (STATE.ui.activeModal.id === 'challenge-modal') {
                    ChallengeManager.declineChallenge();
                } else if (STATE.ui.activeModal.id === 'feedback-modal' || STATE.ui.activeModal.id === 'game-over-modal') {
                    this.closeModal(STATE.ui.activeModal.id);
                }
            }
        });
    }
};

/* ══════════════════════════════════
   AUTH
══════════════════════════════════ */
const AuthManager = {
    get currentUser() { return STATE.auth.currentUser; },
    set currentUser(val) { STATE.auth.currentUser = val; },

    async checkSession() {
        const saved = StorageManager.getUser();
        const token = StorageManager.getToken();
        if (saved && token) {
            this.currentUser = saved;
            UIManager.updateLobbyGreeting();
            try {
                await ApiManager.request(`${API_BASE}/heartbeat`, { method: 'POST' });
                UIManager.showScreen('lobby-screen');
                WebSocketManager.connect(() => {
                    LobbyManager.initializeLobbySynchronization();
                });
                this.startHeartbeat();
            } catch (err) {
                Logger.error('Session validation failed', err);
                this.logout();
            }
        } else {
            this.logout();
        }
    },

    startHeartbeat() {
        if (STATE.auth.heartbeatIntervalId) clearInterval(STATE.auth.heartbeatIntervalId);
        STATE.auth.heartbeatIntervalId = setInterval(async () => {
            if (this.currentUser) {
                try {
                    await ApiManager.request(`${API_BASE}/heartbeat`, { method: 'POST' });
                } catch (e) {
                    Logger.error('Heartbeat check failed', e);
                }
            }
        }, 10000);
    },

    stopHeartbeat() {
        if (STATE.auth.heartbeatIntervalId) {
            clearInterval(STATE.auth.heartbeatIntervalId);
            STATE.auth.heartbeatIntervalId = null;
        }
    },

    debounceUsernameCheck() {
        const val = DomCache.get('reg-username').value.trim();
        const fb = DomCache.get('username-feedback');
        if (STATE.auth.usernameCheckTimeoutId) clearTimeout(STATE.auth.usernameCheckTimeoutId);

        if (val.length < 3) {
            fb.textContent = 'At least 3 characters required';
            fb.className = 'username-feedback text-red';
            return;
        }
        if (val.length > 30) {
            fb.textContent = 'Maximum 30 characters';
            fb.className = 'username-feedback text-red';
            return;
        }
        if (!VALIDATORS.username(val)) {
            fb.textContent = 'Alphanumeric and underscores only';
            fb.className = 'username-feedback text-red';
            return;
        }

        fb.textContent = 'Checking...';
        fb.className = 'username-feedback text-muted-nx';

        STATE.auth.usernameCheckTimeoutId = setTimeout(async () => {
            try {
                const ok = await ApiManager.request(`${API_BASE}/check-username?username=${encodeURIComponent(val)}`);
                fb.textContent = ok ? 'Username available' : 'Username taken';
                fb.className = `username-feedback ${ok ? 'text-cyan' : 'text-red'}`;
            } catch {
                fb.textContent = '';
            }
        }, 500);
    },

    async register() {
        const fn = DomCache.get('reg-fullname').value.trim();
        const u = DomCache.get('reg-username').value.trim();
        const e = DomCache.get('reg-email').value.trim();
        const p = DomCache.get('reg-password').value;

        if (!fn || !u || !e || !p) { UIManager.showToast('Please fill all fields', 'error'); return; }
        if (fn.length > 100) { UIManager.showToast('Full name must be 100 characters or less', 'error'); return; }
        if (u.length < 3 || u.length > 30) { UIManager.showToast('Username must be 3-30 characters', 'error'); return; }
        if (!VALIDATORS.username(u)) { UIManager.showToast('Username: letters, numbers, and underscores only', 'error'); return; }
        if (!VALIDATORS.email(e)) { UIManager.showToast('Please enter a valid email address', 'error'); return; }
        if (p.length < 8) { UIManager.showToast('Password must be at least 8 characters', 'error'); return; }

        const btn = DomCache.get('registerBtn');
        if (btn) { btn.disabled = true; btn.textContent = 'Creating...'; }

        try {
            await ApiManager.request(`${API_BASE}/register`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ fullName: fn, username: u, email: e, password: p })
            });
            UIManager.showToast('Activation link sent! Check your email (including spam folder).', 'success');
            UIManager.showScreen('login-screen');
        } catch (err) {
            let msg = 'Registration failed. Please try again.';
            if (err.data && (err.data.error || err.data.message)) {
                msg = err.data.error || err.data.message;
            }
            UIManager.showToast(msg, 'error');
        } finally {
            if (btn) { btn.disabled = false; btn.textContent = 'Create Account'; }
        }
    },

    async login() {
        const u = DomCache.get('login-username').value.trim();
        const p = DomCache.get('login-password').value;

        if (!u || !p) { UIManager.showToast('Please enter your username and password', 'error'); return; }

        const btn = DomCache.get('loginBtn');
        if (btn) { btn.disabled = true; btn.textContent = 'Signing in...'; }

        try {
            const data = await ApiManager.request(`${API_BASE}/login`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: u, password: p })
            });

            this.currentUser = data.user.username;
            StorageManager.setUser(this.currentUser);
            StorageManager.setToken(data.token);
            UIManager.updateLobbyGreeting();

            await ApiManager.request(`${API_BASE}/sync`, { method: 'POST' });
            this.startHeartbeat();
            UIManager.showScreen('lobby-screen');
            WebSocketManager.connect(() => {
                LobbyManager.initializeLobbySynchronization();
            });
        } catch (err) {
            let msg = 'Login failed. Check your username and password.';
            if (err.data && (err.data.error || err.data.message)) {
                msg = err.data.error || err.data.message;
            }
            UIManager.showToast(msg, 'error');
        } finally {
            if (btn) { btn.disabled = false; btn.textContent = 'Enter Arena'; }
        }
    },

    logout() {
        StorageManager.clearAll();
        this.stopHeartbeat();

        // SAFE GUARD: Check if LobbyManager exists and has the method before calling it
        if (typeof LobbyManager !== 'undefined' && typeof LobbyManager.clearTimers === 'function') {
            LobbyManager.clearTimers();
        } else {
            console.warn("LobbyManager or clearTimers method not initialized yet during this logout cycle.");
        }

        if (STATE.auth.usernameCheckTimeoutId) clearTimeout(STATE.auth.usernameCheckTimeoutId);

        WebSocketManager.disconnect();
        GameManager.resetLocalFields();

        this.currentUser = '';
        STATE.recovery.pendingEmail = '';
        STATE.recovery.recoveryMode = '';

        UIManager.showScreen('home-screen');
    }
};

/* ══════════════════════════════════
   RECOVERY
══════════════════════════════════ */
const RecoveryManager = {
    get pendingEmail() { return STATE.recovery.pendingEmail; },
    set pendingEmail(val) { STATE.recovery.pendingEmail = val; },
    get recoveryMode() { return STATE.recovery.recoveryMode; },
    set recoveryMode(val) { STATE.recovery.recoveryMode = val; },

    showRecovery(mode) {
        this.recoveryMode = mode;
        DomCache.get('recovery-title').textContent = mode === 'USERNAME' ? 'Recover Username' : 'Reset Password';
        DomCache.get('btn-recovery-submit').textContent = mode === 'USERNAME' ? 'Get Username' : 'Update Password';
        DomCache.get('password-reset-fields').style.display = mode === 'PASSWORD' ? 'block' : 'none';
        DomCache.get('recovery-step-1').style.display = 'block';
        DomCache.get('recovery-step-2').style.display = 'none';
        UIManager.showScreen('recovery-screen');
    },

    async sendRecoveryOtp() {
        const email = DomCache.get('recovery-email').value.trim();
        if (!email) return UIManager.showToast('Please enter your email', 'error');
        if (!VALIDATORS.email(email)) return UIManager.showToast('Please enter a valid email', 'error');

        const btn = document.querySelector('#recovery-step-1 .nx-btn');
        if (btn) { btn.disabled = true; btn.textContent = 'Sending...'; }

        try {
            await ApiManager.request(`${RECOVERY_BASE}/send-otp`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email })
            });
            this.pendingEmail = email;
            DomCache.get('recovery-step-1').style.display = 'none';
            DomCache.get('recovery-step-2').style.display = 'block';
            UIManager.showToast('OTP sent to your email!', 'success');
        } catch (err) {
            let msg = 'Email not found';
            if (err.data && err.data.error) msg = err.data.error;
            UIManager.showToast(msg, 'error');
        } finally {
            if (btn) { btn.disabled = false; btn.textContent = 'Send OTP'; }
        }
    },

    async handleRecoverySubmit() {
        const otp = DomCache.get('recovery-otp').value.trim();
        if (!otp) { UIManager.showToast('Enter the OTP', 'error'); return; }
        if (!VALIDATORS.otp(otp)) { UIManager.showToast('OTP must be 6 digits', 'error'); return; }

        const btn = DomCache.get('btn-recovery-submit');
        if (btn) btn.disabled = true;

        try {
            if (this.recoveryMode === 'USERNAME') {
                const data = await ApiManager.request(`${RECOVERY_BASE}/verify-username`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ email: this.pendingEmail, otp: otp, newPassword: '' })
                });
                UIManager.showToast(`Your username: ${data.username}`, 'success');
                UIManager.showScreen('login-screen');
            } else {
                const newPass = DomCache.get('recovery-new-password').value;
                if (!newPass) { UIManager.showToast('Enter new password', 'error'); return; }
                if (newPass.length < 8) { UIManager.showToast('Password must be at least 8 characters', 'error'); return; }

                await ApiManager.request(`${RECOVERY_BASE}/reset-password`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ email: this.pendingEmail, otp: otp, newPassword: newPass })
                });
                UIManager.showToast('Password reset successful!', 'success');
                UIManager.showScreen('login-screen');
            }
        } catch (err) {
            let msg = 'Invalid OTP or operation failed';
            if (err.data && err.data.error) msg = err.data.error;
            UIManager.showToast(msg, 'error');
        } finally {
            if (btn) btn.disabled = false;
        }
    }
};

/* ══════════════════════════════════
   LOBBY
══════════════════════════════════ */
const LobbyManager = {
    initializeLobbySynchronization: function () {
        if (STATE.lobby.pollingIntervalId) clearInterval(STATE.lobby.pollingIntervalId);
        if (STATE.lobby.leaderboardIntervalId) clearInterval(STATE.lobby.leaderboardIntervalId);

        // Perform initial reliable loading
        this.refreshLobby();
        this.refreshLeaderboard();

        // Establish long-term continuous sync fallbacks
        STATE.lobby.pollingIntervalId = setInterval(() => this.refreshLobby(), 30000);
        STATE.lobby.leaderboardIntervalId = setInterval(() => this.refreshLeaderboard(), 60000);
    },

    clearTimers: function () {
        if (STATE.lobby.pollingIntervalId) {
            clearInterval(STATE.lobby.pollingIntervalId);
            STATE.lobby.pollingIntervalId = null;
        }
        if (STATE.lobby.leaderboardIntervalId) {
            clearInterval(STATE.lobby.leaderboardIntervalId);
            STATE.lobby.leaderboardIntervalId = null;
        }
    },

    filterLobby: function () {
        const searchInput = DomCache.get('lobby-search');
        if (!searchInput) return;
        const query = searchInput.value.toLowerCase().trim();
        const list = DomCache.get('online-users-list');
        if (!list) return;
        const rows = list.getElementsByClassName('player-row');

        for (let row of rows) {
            const username = row.getAttribute('data-username') || '';
            if (username.toLowerCase().includes(query)) {
                row.style.display = '';
            } else {
                row.style.display = 'none';
            }
        }
    },

    refreshLobby: async function () {
        const list = DomCache.get('online-users-list');
        if (!list) return;

        // CRITICAL FIX: Removed brittle CSS .style.display === 'none' guard trap
        // Data model hydration must execute safely regardless of transient UI animation frames.

        try {
            const users = await ApiManager.request(`${API_BASE}/lobby`, {method: 'GET'});
            list.innerHTML = '';

            // Exclude self cleanly using centralized STATE metrics
            const others = users.filter(u => u.username !== STATE.auth.currentUser);

            if (others.length === 0) {
                list.innerHTML = `
                    <div class="empty-lobby">
                        <i class="fas fa-user-slash"></i>
                        <p>No other players online right now</p>
                    </div>`;
                return;
            }

            others.forEach(user => {
                const statusClass = user.status === 'ONLINE' ? 'status-online' : 'status-ingame';
                const statusText = user.status === 'ONLINE' ? 'Available' : 'In Game';
                const actionButton = user.status === 'ONLINE'
                    ? `<button class="btn btn-primary btn-sm btn-challenge" onclick="window.sendChallenge('${user.username}')">
                         <i class="fas fa-swords"></i> Challenge
                       </button>`
                    : `<button class="btn btn-secondary btn-sm btn-challenge" disabled>
                         <i class="fas fa-lock"></i> Busy
                       </button>`;

                const row = document.createElement('div');
                row.className = 'player-row fade-in';
                row.setAttribute('data-username', user.username);
                row.innerHTML = `
                    <div class="player-info">
                        <div class="player-avatar-wrapper">
                            <div class="player-avatar text-avatar">${user.username.charAt(0).toUpperCase()}</div>
                            <span class="status-indicator ${statusClass}"></span>
                        </div>
                        <div class="player-details">
                            <span class="player-name">${UIManager.sanitizeText(user.username)}</span>
                            <span class="player-status-text">${statusText}</span>
                        </div>
                    </div>
                    <div class="player-actions">
                        ${actionButton}
                    </div>
                `;
                list.appendChild(row);
            });
        } catch (err) {
            Logger.error('Failed to sync or refresh lobby data layout:', err);
        }
    },

    refreshLeaderboard: async function () {
        const list = DomCache.get('leaderboard-list');
        if (!list) return;

        try {
            const data = await ApiManager.request(`${API_BASE}/leaderboard`, {method: 'GET'});
            list.innerHTML = '';

            if (data.length === 0) {
                list.innerHTML = '<div class="empty-lobby"><p>No tournament records found</p></div>';
                return;
            }

            data.forEach((entry, index) => {
                const row = document.createElement('div');
                row.className = 'leaderboard-row';
                row.innerHTML = `
                    <div class="rank-info">
                        <span class="rank-number">${index + 1}</span>
                        <span class="rank-name">${UIManager.sanitizeText(entry.username)}</span>
                    </div>
                    <div class="rank-stats">
                        <span class="rank-wins"><i class="fas fa-trophy"></i> ${entry.wins}</span>
                    </div>
                `;
                list.appendChild(row);
            });
        } catch (err) {
            Logger.error('Failed to clear or pull leaderboard array snapshot:', err);
        }
    },

    updateSingleUserStatus(update) {
        // FIX 3: Prevent infinite fetch loops triggered by own status broadcasts
        if (update.username === AuthManager.currentUser) return;

        Logger.info('Lobby Status update', update.username, update.status);
        const list = DomCache.get('online-users-list');
        if (!list) return;
        const row = list.querySelector(`[data-username="${CSS.escape(update.username)}"]`);

        if (update.status === 'OFFLINE') {
            if (row) row.remove();
            return;
        }

        if (!row) {
            this.refreshLobby();
            return;
        }

        const pill = row.querySelector('.status-pill');
        const btn = row.querySelector('.challenge-btn');
        if (!pill || !btn) return;

        if (update.status === 'IN_GAME') {
            pill.className = 'status-pill status-ingame';
            pill.textContent = 'In Game';
            btn.disabled = true;
        } else if (update.status === 'ONLINE') {
            pill.className = 'status-pill status-online';
            pill.textContent = 'Online';
            btn.disabled = false;
        }
    }
};

/* ══════════════════════════════════
   CHALLENGES
══════════════════════════════════ */
const ChallengeManager = {
    get challengePending() { return STATE.lobby.challengePending; },
    set challengePending(val) { STATE.lobby.challengePending = val; },

    sendChallenge(targetUser) {
        if (this.challengePending || GameManager.machineState !== 'IDLE') return;
        this.challengePending = true;
        GameManager.setMachineState('WAITING_ACCEPTANCE');

        const row = DomCache.get('online-users-list').querySelector(`[data-username="${CSS.escape(targetUser)}"]`);
        if (row) {
            const btn = row.querySelector('.challenge-btn');
            if (btn) btn.disabled = true;
        }

        GameManager.currentRoomId = [AuthManager.currentUser, targetUser].sort().join('_');
        GameManager.opponentUser = targetUser;

        DomCache.get('waiting-text').textContent = `Waiting for ${targetUser}...`;
        UIManager.showModal('waiting-modal');

        WebSocketManager.send('/app/challenge', {
            sender: AuthManager.currentUser,
            receiver: targetUser,
            roomId: GameManager.currentRoomId,
            type: 'CHALLENGE_REQUEST'
        });
    },

    handleChallengeMessage(message) {
        if (!WebSocketManager.validatePayload(message)) return;

        if (message.type === 'CHALLENGE_REQUEST') {
            if (GameManager.currentRoomId || this.challengePending || GameManager.machineState !== 'IDLE') {
                return;
            }
            this.challengePending = true;
            GameManager.setMachineState('WAITING_CHALLENGE');
            GameManager.opponentUser = message.sender;
            GameManager.currentRoomId = message.roomId;

            DomCache.get('challenge-text').textContent = `Challenge from ${message.sender}!`;
            UIManager.showModal('challenge-modal');
        } else if (message.type === 'CHALLENGE_RESPONSE') {
            UIManager.closeModal('waiting-modal');
            this.challengePending = false;

            if (message.status === 'ACCEPTED') {
                GameManager.setupGame(message.roomId, message.sender);
            } else if (message.status === 'REJECTED') {
                UIManager.showToast(`${message.sender} declined.`, 'warning');
                GameManager.setMachineState('IDLE');
                LobbyManager.refreshLobby();
            } else if (message.status === 'CANCELLED') {
                UIManager.showToast('Challenge cancelled.', 'info');
                GameManager.setMachineState('IDLE');
                LobbyManager.refreshLobby();
            }
        }
    },

    acceptChallenge() {
        if (!GameManager.currentRoomId || !GameManager.opponentUser) return;
        WebSocketManager.send('/app/challenge/reply', {
            sender: AuthManager.currentUser,
            receiver: GameManager.opponentUser,
            roomId: GameManager.currentRoomId,
            status: 'ACCEPTED',
            type: 'CHALLENGE_RESPONSE'
        });
        UIManager.closeModal('challenge-modal');
        this.challengePending = false;
        GameManager.setupGame(GameManager.currentRoomId, GameManager.opponentUser);
    },

    declineChallenge() {
        if (!GameManager.currentRoomId || !GameManager.opponentUser) return;
        WebSocketManager.send('/app/challenge/reply', {
            sender: AuthManager.currentUser,
            receiver: GameManager.opponentUser,
            roomId: GameManager.currentRoomId,
            status: 'REJECTED',
            type: 'CHALLENGE_RESPONSE'
        });
        UIManager.closeModal('challenge-modal');
        this.challengePending = false;
        GameManager.setMachineState('IDLE');
        LobbyManager.refreshLobby();
    }
};

/* ══════════════════════════════════
   GAME
══════════════════════════════════ */
const GameManager = {
    get machineState() { return STATE.game.machineState; },
    set machineState(val) { STATE.game.machineState = val; },
    get opponentUser() { return STATE.game.opponentUser; },
    set opponentUser(val) { STATE.game.opponentUser = val; },
    get currentRoomId() { return STATE.game.currentRoomId; },
    set currentRoomId(val) { STATE.game.currentRoomId = val; },
    get isMyTurn() { return STATE.game.isMyTurn; },
    set isMyTurn(val) { STATE.game.isMyTurn = val; },
    get isGameOver() { return STATE.game.isGameOver; },
    set isGameOver(val) { STATE.game.isGameOver = val; },
    get mySymbol() { return STATE.game.mySymbol; },
    set mySymbol(val) { STATE.game.mySymbol = val; },
    get tossSubmitted() { return STATE.game.tossSubmitted; },
    set tossSubmitted(val) { STATE.game.tossSubmitted = val; },
    get tossGameStartHandled() { return STATE.game.tossGameStartHandled; },
    set tossGameStartHandled(val) { STATE.game.tossGameStartHandled = val; },
    get boardLocked() { return STATE.game.boardLocked; },
    set boardLocked(val) { STATE.game.boardLocked = val; },

    setMachineState: function (state) {
        STATE.game.engineState = state;
        Logger.info(`Game machine altered boundaries to target state: ${state}`);
    },

    setupGame: function (roomId, opponent) {
        this.setMachineState('PLAYING');
        STATE.game.currentRoomId = roomId;
        STATE.game.opponentUser = opponent;
        STATE.game.isGameOver = false;
        this.resetBoardState();

        UIManager.setRoomDisplay(STATE.auth.currentUser, opponent);
        UIManager.showScreen('game-screen');
        DomCache.get('game-over-modal').style.display = 'none';

        // Bind operational subscriptions dynamically
        WebSocketManager.subscribeRoom(roomId);
    },

    resetLocalFields: function () {
        STATE.game.currentRoomId = null;
        STATE.game.opponentUser = null;
        STATE.game.mySign = null;
        STATE.game.opponentSign = null;
        STATE.game.isMyTurn = false;
        STATE.game.isGameOver = false;
        this.resetBoardState();
    },

    resetGameState: function () {
        this.resetLocalFields();
        this.setMachineState('IDLE');
    },

    resetBoardState: function () {
        STATE.game.board = Array(9).fill('');
        const cells = document.getElementsByClassName('cell');
        for (let cell of cells) {
            cell.textContent = '';
            cell.className = 'cell';
            cell.style.pointerEvents = 'auto';
        }
    },

    sendToss: function () {
        const roomId = STATE.game.currentRoomId;
        if (!roomId || !WebSocketManager.isConnected) return;
        this.setMachineState('TOSS_PENDING');
        UIManager.showModal('toss-modal');
    },

    submitTossChoice: function (choice) {
        const roomId = STATE.game.currentRoomId;
        if (!roomId || !WebSocketManager.isConnected) return;
        WebSocketManager.send(`/app/toss/decision/${roomId}`, { payload: choice });
        UIManager.closeModal('toss-modal');
    },

    requestRematch: function () {
        const roomId = STATE.game.currentRoomId;
        if (!roomId || !WebSocketManager.isConnected) return;
        WebSocketManager.send(`/app/game/rematch/${roomId}`, { payload: 'REMATCH_REQUEST' });
    },

    leaveGame() {
        Logger.info('leaveGame triggered cleanly without timers');
        const roomId = this.currentRoomId;

        UIManager.closeModal('game-over-modal');
        UIManager.closeModal('toss-modal');
        UIManager.closeModal('waiting-modal');
        UIManager.closeModal('challenge-modal');

        if (roomId && WebSocketManager.isConnected) {
            // FIX 1: Correct path mismatch to match backend Controller
            WebSocketManager.send('/app/game/abort', {
                sender: AuthManager.currentUser,
                roomId: roomId,
                type: 'GAME_ABORTED'
            });
        }
        this.cleanupGameState();
    },

    cleanupGameState() {
        WebSocketManager.unsubscribeRoom();
        this.resetLocalFields();
        UIManager.showScreen('lobby-screen');

        // FIX 2: Introduce 250ms transactional alignment delay.
        // Gives PostgreSQL time to commit the STOMP abort before the REST query hits.
        setTimeout(() => {
            LobbyManager.initializeLobbySynchronization();
        }, 250);
    },

    sendMove: function (pos) {
        const roomId = STATE.game.currentRoomId;
        if (!roomId || !STATE.game.isMyTurn || STATE.game.isGameOver || !WebSocketManager.isConnected) return;
        if (STATE.game.board[pos] !== '') return;

        WebSocketManager.send(`/app/game.move/${roomId}`, { boardPosition: pos });
    },

    handleRoomMessage: function (payload) {
        if (!payload || !payload.type) return;

        switch (payload.type) {
            case 'TOSS_RESULT':
                UIManager.closeModal('toss-modal');
                if (payload.payload === STATE.auth.currentUser) {
                    STATE.game.mySign = 'X';
                    STATE.game.opponentSign = 'O';
                    STATE.game.isMyTurn = true;
                    UIManager.showToast('You won the toss! You are X (First)', 'success');
                } else {
                    STATE.game.mySign = 'O';
                    STATE.game.opponentSign = 'X';
                    STATE.game.isMyTurn = false;
                    UIManager.showToast(`${STATE.game.opponentUser} won the toss. You are O`, 'info');
                }
                this.updateTurnDisplay();
                break;

            case 'MOVE_UPDATE':
                const movePos = payload.boardPosition;
                const player = payload.sender;
                const symbol = (player === STATE.auth.currentUser) ? STATE.game.mySign : STATE.game.opponentSign;

                STATE.game.board[movePos] = symbol;
                const cell = document.querySelector(`.cell[onclick="window.sendMove(${movePos})"]`);
                if (cell) {
                    cell.textContent = symbol;
                    cell.classList.add(symbol.toLowerCase() === 'x' ? 'x-mark' : 'o-mark');
                    cell.style.pointerEvents = 'none';
                }

                if (!STATE.game.isGameOver) {
                    STATE.game.isMyTurn = (player !== STATE.auth.currentUser);
                    this.updateTurnDisplay();
                }
                break;

            case 'GAME_OVER':
                STATE.game.isGameOver = true;
                this.setMachineState('GAME_OVER');
                UIManager.showWinnerModal(payload.payload);
                break;

            case 'GAME_ABORTED':
                STATE.game.isGameOver = true;
                UIManager.showToast('Opponent left or aborted the match context.', 'warning');
                this.cleanupGameState();
                break;

            case 'REMATCH_OFFER':
                if (payload.sender !== STATE.auth.currentUser) {
                    UIManager.showToast(`${STATE.game.opponentUser} requested a rematch! Click Rematch to accept.`, 'info');
                }
                break;

            case 'REMATCH_ACCEPTED':
                UIManager.showToast('Rematch started!', 'success');
                this.setupGame(STATE.game.currentRoomId, STATE.game.opponentUser);
                break;
        }
    },
    showWinnerModal(state) {
        const title = DomCache.get('go-title');
        const icon = DomCache.get('go-icon');
        const desc = DomCache.get('go-desc');

        if (state === 'DRAW') {
            icon.textContent = 'Draw';
            title.textContent = "It's a Draw!";
            title.className = 'modal-title';
            desc.textContent = 'A hard-fought battle with no victor. Well played.';
        } else {
            const winSym = state.replace('WINNER_', '');
            const won = winSym === this.mySymbol;
            icon.textContent = won ? 'Victory' : 'Defeat';
            title.textContent = won ? 'Victory!' : `${this.opponentUser} Won`;
            title.className = `modal-title ${won ? 'text-cyan' : 'text-red'}`;
            desc.textContent = won
                ? 'Outstanding performance in the arena.'
                : 'Better luck next round. Keep fighting.';
        }
        UIManager.showModal('game-over-modal');
    },

    updateTurnDisplay: function () {
        const turnIndicator = document.getElementById('turn-indicator');
        if (!turnIndicator) return;
        if (STATE.game.isMyTurn) {
            turnIndicator.innerHTML = `<span class="badge bg-success animate-pulse">Your Turn (${STATE.game.mySign})</span>`;
        } else {
            turnIndicator.innerHTML = `<span class="badge bg-secondary">Opponent's Turn (${STATE.game.opponentSign})</span>`;
        }
    }
};

/* ══════════════════════════════════
   WEBSOCKET
══════════════════════════════════ */
const WebSocketManager = {
    get stompClient() { return STATE.websocket.stompClient; },
    set stompClient(val) { STATE.websocket.stompClient = val; },
    get isConnected() { return STATE.websocket.isConnected; },
    set isConnected(val) { STATE.websocket.isConnected = val; },
    get reconnectAttempt() { return STATE.websocket.reconnectAttempt; },
    set reconnectAttempt(val) { STATE.websocket.reconnectAttempt = val; },
    get maxReconnectDelay() { return STATE.websocket.maxReconnectDelay; },
    get baseReconnectDelay() { return STATE.websocket.baseReconnectDelay; },
    get reconnectTimeoutId() { return STATE.websocket.reconnectTimeoutId; },
    set reconnectTimeoutId(val) { STATE.websocket.reconnectTimeoutId = val; },

    connect(afterConnectCallback) {
        if (this.isConnected && this.stompClient && this.stompClient.connected) {
            if (afterConnectCallback) afterConnectCallback();
            return;
        }

        if (this.stompClient) {
            this.disconnect();
        }

        const socket = new SockJS(WS_ENDPOINT);
        this.stompClient = Stomp.over(socket);
        this.stompClient.debug = null;

        const token = StorageManager.getToken();
        this.stompClient.connect(
            { Authorization: `Bearer ${token}` },
            () => {
                this.isConnected = true;
                this.reconnectAttempt = 0;
                UIManager.setStatus('Connected', 'success');
                if (GameManager.machineState === 'DISCONNECTED') {
                    GameManager.setMachineState('IDLE');
                }

                this.subscribeLobby();
                this.subscribeChallenges();

                if (afterConnectCallback) afterConnectCallback();
            },
            () => {
                this.isConnected = false;
                UIManager.setStatus('Disconnected. Reconnecting...', 'danger');
                GameManager.setMachineState('DISCONNECTED');
                this.handleReconnect(afterConnectCallback);
            }
        );
    },

    disconnect() {
        this.unsubscribeAll();
        if (this.stompClient) {
            try { this.stompClient.disconnect(); } catch (e) { Logger.error('WS disconnect error', e); }
            this.stompClient = null;
        }
        this.isConnected = false;
        if (this.reconnectTimeoutId) {
            clearTimeout(this.reconnectTimeoutId);
            this.reconnectTimeoutId = null;
        }
    },

    handleReconnect(callback) {
        if (this.reconnectTimeoutId) clearTimeout(this.reconnectTimeoutId);

        const jitter = Math.random() * 1000;
        const delay = Math.min(
            this.baseReconnectDelay * Math.pow(2, this.reconnectAttempt) + jitter,
            this.maxReconnectDelay
        );
        this.reconnectAttempt++;

        const savedRoomId = GameManager.currentRoomId;
        const savedOpponent = GameManager.opponentUser;

        this.reconnectTimeoutId = setTimeout(() => {
            this.connect(() => {
                LobbyManager.initializeLobbySynchronization();
                if (savedRoomId && savedOpponent) {
                    this.subscribeRoom(savedRoomId);
                }
                if (callback) callback();
            });
        }, delay);
    },

    subscribeLobby() {
        if (STATE.websocket.subscriptions.lobby) {
            STATE.websocket.subscriptions.lobby.unsubscribe();
        }
        STATE.websocket.subscriptions.lobby = this.stompClient.subscribe('/topic/lobby.status', (payload) => {
            const data = UIManager.safeJsonParse(payload.body);
            if (data) LobbyManager.updateSingleUserStatus(data);
        });
    },

    subscribeChallenges() {
        if (STATE.websocket.subscriptions.challenge) {
            STATE.websocket.subscriptions.challenge.unsubscribe();
        }
        const currentUser = AuthManager.currentUser;
        STATE.websocket.subscriptions.challenge = this.stompClient.subscribe(`/topic/challenges/${currentUser}`, (payload) => {
            const data = UIManager.safeJsonParse(payload.body);
            if (data) ChallengeManager.handleChallengeMessage(data);
        });
    },

    subscribeRoom(roomId) {
        if (STATE.websocket.subscriptions.room) {
            STATE.websocket.subscriptions.room.unsubscribe();
        }
        STATE.websocket.subscriptions.room = this.stompClient.subscribe(`/topic/game/${roomId}`, (payload) => {
            const data = UIManager.safeJsonParse(payload.body);
            if (data) GameManager.handleRoomMessage(data);
        });
    },

    unsubscribeRoom() {
        if (STATE.websocket.subscriptions.room) {
            STATE.websocket.subscriptions.room.unsubscribe();
            STATE.websocket.subscriptions.room = null;
        }
    },

    unsubscribeAll() {
        Object.keys(STATE.websocket.subscriptions).forEach(key => {
            if (STATE.websocket.subscriptions[key]) {
                try { STATE.websocket.subscriptions[key].unsubscribe(); } catch (e) {}
                STATE.websocket.subscriptions[key] = null;
            }
        });
    },

    send(destination, body = {}) {
        if (!this.stompClient || !this.stompClient.connected) {
            Logger.warn(`WS connection dead. Blocked send to ${destination}`);
            return false;
        }
        this.stompClient.send(destination, {}, JSON.stringify(body));
        return true;
    },

    validatePayload(payload) {
        if (!payload || typeof payload !== 'object') return false;
        return true;
    }
};

/* ══════════════════════════════════
   BOOTSTRAP
══════════════════════════════════ */
function setupVisibilityAndNetworkListeners() {
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible' && AuthManager.currentUser) {
            LobbyManager.refreshLobby();
            LobbyManager.refreshLeaderboard();
        }
    });

    window.addEventListener('online', () => {
        UIManager.showToast('Network connection restored.', 'success');
        if (AuthManager.currentUser) {
            WebSocketManager.connect(() => {
                LobbyManager.initializeLobbySynchronization();
            });
        }
    });

    window.addEventListener('offline', () => {
        UIManager.showToast('Network connection lost. Operating in offline mode.', 'error');
        WebSocketManager.isConnected = false;
        UIManager.setStatus('Offline', 'danger');
        GameManager.setMachineState('DISCONNECTED');
    });
}

window.onload = async function () {
    DomCache.init();
    UIManager.setupGlobalListeners();
    setupVisibilityAndNetworkListeners();
    await AuthManager.checkSession();
};

window.debounceUsernameCheck = () => AuthManager.debounceUsernameCheck();
window.register = () => AuthManager.register();
window.login = () => AuthManager.login();
window.logout = () => AuthManager.logout();
window.showRecovery = (mode) => RecoveryManager.showRecovery(mode);
window.sendRecoveryOtp = () => RecoveryManager.sendRecoveryOtp();
window.handleRecoverySubmit = () => RecoveryManager.handleRecoverySubmit();
window.filterLobby = () => LobbyManager.filterLobby();
window.sendChallenge = (targetUser) => ChallengeManager.sendChallenge(targetUser);
window.acceptChallenge = () => ChallengeManager.acceptChallenge();
window.declineChallenge = () => ChallengeManager.declineChallenge();
window.requestRematch = () => GameManager.requestRematch();
window.leaveGame = () => GameManager.leaveGame();
window.sendToss = () => GameManager.sendToss();
window.submitTossChoice = (choice) => GameManager.submitTossChoice(choice);
window.sendMove = (pos) => GameManager.sendMove(pos);
window.rateStar = (n) => UIManager.rateStar(n);
window.submitFeedback = () => UIManager.submitFeedback();
window.showScreen = (screenId) => UIManager.showScreen(screenId);