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
        LobbyManager.clearTimers();
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
    initializeLobbySynchronization() {
        this.clearTimers();
        this.refreshLobby();
        this.refreshLeaderboard();
        STATE.lobby.lobbyIntervalId = setInterval(() => this.refreshLobby(), 300000);
        STATE.lobby.leaderboardIntervalId = setInterval(() => this.refreshLeaderboard(), 300000);
    },

    clearTimers() {
        if (STATE.lobby.lobbyIntervalId) clearInterval(STATE.lobby.lobbyIntervalId);
        if (STATE.lobby.leaderboardIntervalId) clearInterval(STATE.lobby.leaderboardIntervalId);
        STATE.lobby.lobbyIntervalId = null;
        STATE.lobby.leaderboardIntervalId = null;
    },

    filterLobby() {
        const qInput = DomCache.get('player-search');
        if (!qInput) return;
        const q = qInput.value.toLowerCase();
        const rows = DomCache.get('online-users-list').querySelectorAll('.user-item-row');
        rows.forEach(item => {
            const u = item.getAttribute('data-username').toLowerCase();
            const f = (item.getAttribute('data-fullname') || '').toLowerCase();
            item.style.display = (u.includes(q) || f.includes(q)) ? 'flex' : 'none';
        });
    },

    async refreshLobby() {
        if (DomCache.get('online-users-list').closest('.screen').style.display === 'none') return;
        try {
            const users = await ApiManager.request(`${API_BASE}/lobby`);
            const list = DomCache.get('online-users-list');
            const others = users.filter(u => u.username !== AuthManager.currentUser);

            if (!others.length) {
                list.innerHTML = '<div style="text-align:center;padding:32px;color:var(--muted);font-size:0.82rem;">No other players online</div>';
                return;
            }

            const fragment = document.createDocumentFragment();
            others.forEach(user => {
                const busy = user.status === 'IN_GAME';
                const pillClass = busy ? 'status-ingame' : 'status-online';
                const pillText = busy ? 'In Game' : 'Online';
                const initials = user.username.slice(0, 2).toUpperCase();
                const safeName = UIManager.sanitizeText(user.username);
                const safeFullName = UIManager.sanitizeText(user.fullName || 'Nexus Player');

                const row = document.createElement('div');
                row.className = 'player-row user-item-row';
                row.setAttribute('data-username', safeName);
                row.setAttribute('data-fullname', safeFullName);
                row.innerHTML = `
                    <div class="player-info">
                        <div class="player-avatar">${initials}</div>
                        <div>
                            <div class="player-name">${safeName}</div>
                            <div class="player-fullname">${safeFullName}</div>
                        </div>
                    </div>
                    <div style="display:flex;align-items:center;gap:8px;">
                        <span class="status-pill ${pillClass}">${pillText}</span>
                        <button class="challenge-btn" data-target="${safeName}" ${busy ? 'disabled' : ''}>Challenge</button>
                    </div>
                `;

                const btn = row.querySelector('.challenge-btn');
                if (btn && !busy) {
                    btn.addEventListener('click', () => ChallengeManager.sendChallenge(safeName));
                }
                fragment.appendChild(row);
            });

            list.innerHTML = '';
            list.appendChild(fragment);
            this.filterLobby();
        } catch (e) {
            Logger.error('Lobby refresh failed', e);
        }
    },

    async refreshLeaderboard() {
        try {
            const players = await ApiManager.request(`${API_BASE}/leaderboard`);
            const list = DomCache.get('leaderboard-list');

            if (!players.length) {
                list.innerHTML = '<div style="text-align:center;padding:24px;color:var(--muted);font-size:0.8rem;">No games played yet</div>';
                return;
            }

            const fragment = document.createDocumentFragment();
            players.forEach((p, i) => {
                const total = (p.wins || 0) + (p.losses || 0);
                const rate = p.winRate !== undefined ? p.winRate : (total > 0 ? Math.round((p.wins / total) * 100) : 0);
                const medal = i < 3 ? ['🥇','🥈','🥉'][i] : `#${i + 1}`;

                const row = document.createElement('div');
                row.className = 'lb-row';
                row.innerHTML = `
                    <span class="lb-rank">${medal}</span>
                    <span class="lb-name">${UIManager.sanitizeText(p.username)}</span>
                    <div class="lb-stats">
                        <span class="lb-win">${p.wins || 0}W</span>
                        <span class="lb-loss">${p.losses || 0}L</span>
                        <span class="lb-rate">${rate}%</span>
                    </div>
                `;
                fragment.appendChild(row);
            });

            list.innerHTML = '';
            list.appendChild(fragment);
        } catch (e) {
            Logger.error('Leaderboard load failed', e);
        }
    },

    updateSingleUserStatus(update) {
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

    setMachineState(state) {
        Logger.info(`Machine State: ${this.machineState} -> ${state}`);
        this.machineState = state;
    },

    setupGame(roomId, opponent) {
        this.currentRoomId = roomId;
        this.opponentUser = opponent;
        this.setMachineState('IN_ROOM');

        const parts = roomId.split('_');
        UIManager.setRoomDisplay(parts[0], parts[1]);
        UIManager.showScreen('game-container');

        WebSocketManager.subscribeRoom(roomId);
        this.resetBoardState();
    },

    resetLocalFields() {
        this.isGameOver = false;
        this.isMyTurn = false;
        this.mySymbol = '';
        this.tossSubmitted = false;
        this.tossGameStartHandled = false;
        this.currentRoomId = '';
        this.opponentUser = null;
        this.boardLocked = false;
        this.setMachineState('IDLE');
    },

    resetGameState() {
        this.isGameOver = false;
        this.isMyTurn = false;
        this.mySymbol = '';
        this.tossSubmitted = false;
        this.tossGameStartHandled = false;
        this.boardLocked = false;

        const cells = DomCache.get('cells');
        for (let i = 0; i < cells.length; i++) {
            cells[i].textContent = '';
            cells[i].className = 'cell';
        }
    },

    resetBoardState() {
        this.resetGameState();
        this.setMachineState('TOSS_PENDING');

        const parts = this.currentRoomId.split('_');
        const amHost = parts[0] === AuthManager.currentUser;
        const tossBtn = DomCache.get('btn-toss');

        if (amHost) {
            tossBtn.style.display = 'inline-block';
            UIManager.setStatus('You are Host — flip the coin to begin!', 'info');
        } else {
            tossBtn.style.display = 'none';
            UIManager.setStatus(`Waiting for ${this.opponentUser} to flip...`, 'warn');
        }
    },

    sendToss() {
        if (this.machineState !== 'TOSS_PENDING' || !this.currentRoomId || !this.opponentUser) {
            UIManager.showToast('Match environment not ready for toss.', 'warning');
            return;
        }
        WebSocketManager.send(`/app/toss/${this.currentRoomId}`, {
            playerOne: AuthManager.currentUser,
            playerTwo: this.opponentUser,
            roomId: this.currentRoomId
        });
        DomCache.get('btn-toss').style.display = 'none';
        UIManager.setStatus('Flipping the coin...', 'info');
    },

    submitTossChoice(choice) {
        if (this.tossSubmitted) return;
        this.tossSubmitted = true;

        DomCache.get('tossChoiceBtns').forEach(b => { b.disabled = true; });
        WebSocketManager.send(`/app/toss/decision/${this.currentRoomId}`, {
            payload: choice
        });
        UIManager.closeModal('toss-modal');
    },

    requestRematch() {
        if (this.machineState !== 'GAME_OVER' || !this.currentRoomId) return;
        WebSocketManager.send(`/app/reset/${this.currentRoomId}`, {});
    },

    leaveGame() {
        Logger.info('leaveGame triggered cleanly without timers');
        const roomId = this.currentRoomId;

        UIManager.closeModal('game-over-modal');
        UIManager.closeModal('toss-modal');
        UIManager.closeModal('waiting-modal');
        UIManager.closeModal('challenge-modal');

        if (roomId && WebSocketManager.isConnected) {
            WebSocketManager.send('/app/game.abort', {
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
        LobbyManager.initializeLobbySynchronization();
    },

    sendMove(pos) {
        if (this.machineState !== 'PLAYING' || this.isGameOver || !this.isMyTurn || this.boardLocked) return;

        const cells = DomCache.get('cells');
        if (cells[pos] && cells[pos].textContent !== '') return;

        this.boardLocked = true;
        WebSocketManager.send(`/app/move/${this.currentRoomId}`, {
            playerUsername: AuthManager.currentUser,
            boardPosition: pos,
            roomId: this.currentRoomId,
            symbol: this.mySymbol
        });
        this.isMyTurn = false;
        UIManager.setStatus(`Waiting for ${this.opponentUser}...`, 'warn');
    },

    handleRoomMessage(payload) {
        if (!WebSocketManager.validatePayload(payload)) return;

        if (payload.type === 'GAME_ABORTED') {
            if (payload.sender === AuthManager.currentUser) return;
            UIManager.showToast(`${payload.sender} left the match.`, 'warning');
            this.cleanupGameState();
            return;
        }

        if (payload.type === 'GAME_RESET') {
            UIManager.closeModal('game-over-modal');
            UIManager.closeModal('toss-modal');
            DomCache.get('toss-winner-section').style.display = 'none';
            DomCache.get('toss-loser-section').style.display = 'none';
            DomCache.get('tossChoiceBtns').forEach(b => { b.disabled = false; });
            this.resetBoardState();
            return;
        }

        if (payload.type === 'TOSS') {
            this.setMachineState('TOSS_RESULT');
            DomCache.get('btn-toss').style.display = 'none';
            UIManager.showModal('toss-modal');

            if (payload.payload === AuthManager.currentUser) {
                DomCache.get('toss-modal-card').style.borderColor = 'rgba(0,212,255,0.3)';
                DomCache.get('toss-result-title').textContent = 'You Won the Toss!';
                DomCache.get('toss-result-title').className = 'modal-title text-cyan';
                DomCache.get('toss-result-desc').textContent = 'Pick your symbol to enter the arena:';
                DomCache.get('toss-winner-section').style.display = 'block';
                DomCache.get('toss-loser-section').style.display = 'none';
                UIManager.setStatus('You won the toss! Choose your symbol.', 'success');
            } else {
                DomCache.get('toss-modal-card').style.borderColor = 'rgba(255,201,64,0.25)';
                DomCache.get('toss-result-title').textContent = `${payload.payload} Won`;
                DomCache.get('toss-result-title').className = 'modal-title text-gold';
                DomCache.get('toss-result-desc').textContent = 'Your opponent is choosing their symbol...';
                DomCache.get('toss-winner-section').style.display = 'none';
                DomCache.get('toss-loser-section').style.display = 'block';
                DomCache.get('toss-waiting-text').textContent = `Waiting for ${payload.payload} to choose...`;
                UIManager.setStatus(`${payload.payload} won the toss. Waiting...`, 'warn');
            }
            return;
        }

        if (payload.type === 'TOSS_RESULT') {
            this.setMachineState('PLAYING');
            UIManager.closeModal('toss-modal');
            DomCache.get('btn-toss').style.display = 'none';

            const firstPlayer = payload.payload;
            if (firstPlayer === AuthManager.currentUser) {
                this.isMyTurn = true;
                this.mySymbol = 'X';
                UIManager.setStatus('Your turn! Make a move.', 'success');
            } else {
                this.isMyTurn = false;
                this.mySymbol = 'O';
                UIManager.setStatus(`${this.opponentUser}'s turn...`, 'warn');
            }

            this.tossGameStartHandled = true;
            this.boardLocked = false;
            return;
        }

        if (payload.boardPosition !== undefined && payload.boardPosition !== null) {
            const pos = parseInt(payload.boardPosition, 10);
            const cells = DomCache.get('cells');

            if (cells[pos] && cells[pos].textContent === '') {
                cells[pos].textContent = payload.symbol;
                cells[pos].classList.add(payload.symbol === 'X' ? 'x-mark' : 'o-mark');
                this.boardLocked = false;

                if (payload.gameState && payload.gameState !== 'ONGOING') {
                    this.isGameOver = true;
                    this.setMachineState('GAME_OVER');
                    this.showWinnerModal(payload.gameState);
                } else if (payload.playerUsername !== AuthManager.currentUser) {
                    this.isMyTurn = true;
                    UIManager.setStatus('Your turn!', 'success');
                } else {
                    UIManager.setStatus(`Waiting for ${this.opponentUser}...`, 'warn');
                }
            }
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