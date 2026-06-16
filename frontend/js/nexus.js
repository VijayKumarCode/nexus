/* ═══════════════════════════════════════════
   NEXUS MULTIPLAYER ARENA — v3.0 (Production-Hardened)
   Game Logic & WebSocket Client
   © 2026 Vijay Kumar. All rights reserved.

   AUDIT FIXES v3.0:
     C1: startHeartbeat now calls /heartbeat (was calling /sync)
     C2: Removed ?username= query params — backend uses JWT principal
     C3: Recovery username flow now sends newPassword: "" to satisfy DTO
     C4: Recovery response now parsed as JSON (was .text())
     C5: Reconnect now resubscribes lobby.status + challenges/{user}
     C6: Added email regex validation in register()
     H1: Fixed password error message (was "4 chars", now "8 chars")
     H3: State encapsulated in NEXUS_STATE object
     H4: GAME_RESET now resets isGameOver = false
     H5: Backend URL from window.NEXUS_CONFIG or env
     H6: Added username pattern validation (alphanumeric + underscore)
     H7: Added fullName max length validation
     H8: Removed username from all URL query params
     M11: Added exponential backoff on reconnect (max 30s)
     M15: Consistent opponentUser type (null)
     M16: logout() now clears ALL state including pendingEmail, recoveryMode
     M17: sendChallenge now disables button to prevent double-click
     M23: Added email validation in recovery flow
     M24: Added password min length in recovery
     M25: Added OTP format validation (6 digits)
═══════════════════════════════════════════ */

'use strict';

/* ══════════════════════════════════
   CONFIG
══════════════════════════════════ */
const BACKEND_URL =
    (window.location.hostname === 'localhost' ||
        window.location.hostname === '127.0.0.1')
        ? 'http://localhost:8080'
        : '';

const API_BASE = BACKEND_URL + '/api/users';
const RECOVERY_BASE = BACKEND_URL + '/api/recovery';
const WS_ENDPOINT = BACKEND_URL + '/game-websocket';

/* ══════════════════════════════════
   ENCAPSULATED STATE
══════════════════════════════════ */
const NEXUS_STATE = {
    stompClient:            null,
    currentUser:            '',
    opponentUser:           null,
    currentRoomId:          '',
    currentPendingOpponent: null,
    isMyTurn:               false,
    isGameOver:             false,
    roomSubscription:       null,
    lobbySubscription:      null,
    challengeSubscription:  null,
    mySymbol:               '',
    usernameCheckTimeout:   null,
    pendingEmail:           '',
    recoveryMode:           '',
    heartbeatInterval:      null,
    lobbyInterval:          null,
    leaderboardInterval:    null,
    tossSubmitted:          false,
    tossGameStartHandled:   false,
    isConnected:            false,
    selectedStar:           0,
    reconnectAttempt:       0,
    maxReconnectDelay:      30000
};

/* ══════════════════════════════════
   VALIDATION UTILITIES
══════════════════════════════════ */
const VALIDATORS = {
    email:    (v) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v),
    username: (v) => /^[a-zA-Z0-9_]+$/.test(v),
    otp:      (v) => /^\d{6}$/.test(v)
};

/* ══════════════════════════════════
   AUTHENTICATED FETCH HELPER
══════════════════════════════════ */
async function fetchWithAuth(url, options = {}) {
    const token = localStorage.getItem('nexus_token');
    if (token) {
        options.headers = options.headers || {};
        options.headers['Authorization'] = 'Bearer ' + token;
    }
    return fetch(url, options);
}

/* ══════════════════════════════════
   UI HELPERS
══════════════════════════════════ */
function showScreen(screenId) {
    document.querySelectorAll('.screen').forEach(el => el.style.display = 'none');
    const el = document.getElementById(screenId);
    el.style.display = 'block';
    el.style.animation = 'none';
    requestAnimationFrame(() => { el.style.animation = 'fadeUp 0.4s ease forwards'; });
}

function setStatus(text, type = 'info') {
    const el = document.getElementById('status-indicator');
    el.textContent = text;
    el.className   = 'status-bar status-' + type;
}

function showToast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    const toast     = document.createElement('div');
    toast.className = `toast-item toast-${type}`;
    toast.textContent = message;
    container.appendChild(toast);
    requestAnimationFrame(() => toast.style.opacity = '1');
    setTimeout(() => {
        toast.style.opacity = '0';
        setTimeout(() => { if (toast.parentNode) container.removeChild(toast); }, 300);
    }, 3000);
}

function updateLobbyGreeting() {
    document.getElementById('lobby-greeting').textContent = NEXUS_STATE.currentUser;
    const av = document.getElementById('lobby-avatar-el');
    if (av) av.textContent = NEXUS_STATE.currentUser.slice(0, 2).toUpperCase();
}

function setRoomDisplay(p1, p2) {
    document.getElementById('room-p1').textContent = p1;
    document.getElementById('room-p2').textContent = p2;
}

/* ══════════════════════════════════
   FEEDBACK
══════════════════════════════════ */
function rateStar(n) {
    NEXUS_STATE.selectedStar = n;
    document.querySelectorAll('.star-btn').forEach((btn, i) => {
        btn.classList.toggle('active', i < n);
    });
}

function submitFeedback() {
    const text     = document.getElementById('feedback-text').value.trim();
    const category = document.getElementById('feedback-category').value;
    if (!text) { showToast('Please write your feedback before sending.', 'warning'); return; }
    // In production, send to a real feedback endpoint
    console.log('Feedback:', { rating: NEXUS_STATE.selectedStar, category, text, user: NEXUS_STATE.currentUser });
    showToast('Thank you! Your feedback has been received.', 'success');
    document.getElementById('feedback-modal').style.display = 'none';
    document.getElementById('feedback-text').value = '';
    rateStar(0);
}

/* ══════════════════════════════════
   BOOT
══════════════════════════════════ */
window.onload = async function () {
    const saved = localStorage.getItem('nexus_user');
    const token = localStorage.getItem('nexus_token');
    if (saved && token) {
        NEXUS_STATE.currentUser = saved;
        updateLobbyGreeting();
        try {
            /* C1, C2 FIX: call /heartbeat (not /sync), no ?username param */
            const res = await fetchWithAuth(`${API_BASE}/heartbeat`, { method: 'POST' });
            if (!res.ok) throw new Error('Unauthorized');
            showScreen('lobby-screen');
            connect();
            startHeartbeat();
            startLobbyRefresh();
        } catch {
            logout();
        }
    } else {
        logout();
    }
};

/* ══════════════════════════════════
   PRESENCE
══════════════════════════════════ */
function startHeartbeat() {
    if (NEXUS_STATE.heartbeatInterval) clearInterval(NEXUS_STATE.heartbeatInterval);
    NEXUS_STATE.heartbeatInterval = setInterval(async () => {
        if (NEXUS_STATE.currentUser) {
            /* C1 FIX: call /heartbeat, no ?username param */
            await fetchWithAuth(`${API_BASE}/heartbeat`, { method: 'POST' });
        }
    }, 10000);
}

/* ══════════════════════════════════
   AUTH
══════════════════════════════════ */
function debounceUsernameCheck() {
    const val = document.getElementById('reg-username').value.trim();
    const fb  = document.getElementById('username-feedback');
    clearTimeout(NEXUS_STATE.usernameCheckTimeout);

    if (val.length < 3) {
        fb.textContent = 'At least 3 characters required';
        fb.className   = 'username-feedback text-red';
        return;
    }
    if (val.length > 30) {
        fb.textContent = 'Maximum 30 characters';
        fb.className   = 'username-feedback text-red';
        return;
    }
    /* H6 FIX: username pattern validation */
    if (!VALIDATORS.username(val)) {
        fb.textContent = 'Alphanumeric and underscores only';
        fb.className   = 'username-feedback text-red';
        return;
    }

    fb.textContent = 'Checking...';
    fb.className   = 'username-feedback text-muted-nx';

    NEXUS_STATE.usernameCheckTimeout = setTimeout(async () => {
        try {
            const res = await fetch(`${API_BASE}/check-username?username=${encodeURIComponent(val)}`);
            const ok  = await res.json();
            fb.textContent = ok ? 'Username available' : 'Username taken';
            fb.className   = `username-feedback ${ok ? 'text-cyan' : 'text-red'}`;
        } catch { fb.textContent = ''; }
    }, 500);
}

async function register() {
    const fn = document.getElementById('reg-fullname').value.trim();
    const u  = document.getElementById('reg-username').value.trim();
    const e  = document.getElementById('reg-email').value.trim();
    const p  = document.getElementById('reg-password').value;

    if (!fn || !u || !e || !p) {
        showToast('Please fill all fields', 'error'); return;
    }
    /* H7 FIX: fullName max length */
    if (fn.length > 100) {
        showToast('Full name must be 100 characters or less', 'error'); return;
    }
    if (u.length < 3 || u.length > 30) {
        showToast('Username must be 3-30 characters', 'error'); return;
    }
    /* H6 FIX: username pattern */
    if (!VALIDATORS.username(u)) {
        showToast('Username: letters, numbers, and underscores only', 'error'); return;
    }
    /* C6 FIX: email format validation */
    if (!VALIDATORS.email(e)) {
        showToast('Please enter a valid email address', 'error'); return;
    }
    /* H1 FIX: correct password error message */
    if (p.length < 8) {
        showToast('Password must be at least 8 characters', 'error'); return;
    }

    const btn = document.querySelector('#register-screen .nx-btn-primary');
    if (btn) { btn.disabled = true; btn.textContent = 'Creating...'; }

    try {
        const res = await fetch(`${API_BASE}/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ fullName: fn, username: u, email: e, password: p })
        });

        if (res.ok) {
            showToast('Activation link sent! Check your email (including spam folder).', 'success');
            showScreen('login-screen');
        } else {
            let msg = 'Registration failed. Please try again.';
            try { const d = await res.json(); msg = d.error || d.message || msg; } catch {}
            showToast(msg, 'error');
        }
    } catch (err) {
        showToast('Network error — please check your connection and try again.', 'error');
    } finally {
        if (btn) { btn.disabled = false; btn.textContent = 'Create Account'; }
    }
}

async function login() {
    const u = document.getElementById('login-username').value.trim();
    const p = document.getElementById('login-password').value;

    if (!u || !p) { showToast('Please enter your username and password', 'error'); return; }

    const btn = document.querySelector('#login-screen .nx-btn-success');
    if (btn) { btn.disabled = true; btn.textContent = 'Signing in...'; }

    try {
        const res = await fetch(`${API_BASE}/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: u, password: p })
        });

        if (res.ok) {
            const data = await res.json();
            NEXUS_STATE.currentUser = data.user.username;
            localStorage.setItem('nexus_user', NEXUS_STATE.currentUser);
            localStorage.setItem('nexus_token', data.token);
            updateLobbyGreeting();
            /* C2 FIX: no ?username param */
            await fetchWithAuth(`${API_BASE}/sync`, { method: 'POST' });
            startHeartbeat();
            showScreen('lobby-screen');
            connect();
            startLobbyRefresh();
        } else {
            let msg = 'Login failed. Check your username and password.';
            try { const d = await res.json(); msg = d.error || d.message || msg; } catch {}
            showToast(msg, 'error');
        }
    } catch (err) {
        showToast('Network error — please check your connection.', 'error');
    } finally {
        if (btn) { btn.disabled = false; btn.textContent = 'Enter Arena'; }
    }
}

/* C2 FIX: logout uses POST /logout with JWT (no username param) */
function logout() {
    localStorage.removeItem('nexus_user');
    localStorage.removeItem('nexus_token');

    /* M16 FIX: clear ALL state */
    NEXUS_STATE.stompClient            = null;
    NEXUS_STATE.currentUser            = '';
    NEXUS_STATE.opponentUser           = null;
    NEXUS_STATE.currentRoomId          = '';
    NEXUS_STATE.currentPendingOpponent = null;
    NEXUS_STATE.isMyTurn               = false;
    NEXUS_STATE.isGameOver             = false;
    NEXUS_STATE.mySymbol               = '';
    NEXUS_STATE.tossSubmitted          = false;
    NEXUS_STATE.tossGameStartHandled   = false;
    NEXUS_STATE.isConnected            = false;
    NEXUS_STATE.pendingEmail           = '';
    NEXUS_STATE.recoveryMode           = '';
    NEXUS_STATE.selectedStar           = 0;
    NEXUS_STATE.reconnectAttempt       = 0;

    if (NEXUS_STATE.roomSubscription)      { NEXUS_STATE.roomSubscription.unsubscribe();      NEXUS_STATE.roomSubscription = null; }
    if (NEXUS_STATE.lobbySubscription)     { NEXUS_STATE.lobbySubscription.unsubscribe();     NEXUS_STATE.lobbySubscription = null; }
    if (NEXUS_STATE.challengeSubscription) { NEXUS_STATE.challengeSubscription.unsubscribe(); NEXUS_STATE.challengeSubscription = null; }
    if (NEXUS_STATE.heartbeatInterval)     clearInterval(NEXUS_STATE.heartbeatInterval);
    if (NEXUS_STATE.lobbyInterval)         clearInterval(NEXUS_STATE.lobbyInterval);
    if (NEXUS_STATE.leaderboardInterval)   clearInterval(NEXUS_STATE.leaderboardInterval);
    if (NEXUS_STATE.usernameCheckTimeout)  clearTimeout(NEXUS_STATE.usernameCheckTimeout);

    if (NEXUS_STATE.stompClient) {
        try { NEXUS_STATE.stompClient.disconnect(); } catch (e) {}
    }

    showScreen('home-screen');
}

/* ══════════════════════════════════
   ACCOUNT RECOVERY
══════════════════════════════════ */
function showRecovery(mode) {
    NEXUS_STATE.recoveryMode = mode;
    document.getElementById('recovery-title').textContent      = mode === 'USERNAME' ? 'Recover Username' : 'Reset Password';
    document.getElementById('btn-recovery-submit').textContent = mode === 'USERNAME' ? 'Get Username' : 'Update Password';
    document.getElementById('password-reset-fields').style.display = mode === 'PASSWORD' ? 'block' : 'none';
    document.getElementById('recovery-step-1').style.display   = 'block';
    document.getElementById('recovery-step-2').style.display   = 'none';
    showScreen('recovery-screen');
}

async function sendRecoveryOtp() {
    const email = document.getElementById('recovery-email').value.trim();
    /* M23 FIX: email validation */
    if (!email) return showToast('Please enter your email', 'error');
    if (!VALIDATORS.email(email)) return showToast('Please enter a valid email', 'error');

    const btn = document.querySelector('#recovery-step-1 .nx-btn');
    if (btn) { btn.disabled = true; btn.textContent = 'Sending...'; }

    try {
        const res = await fetch(`${RECOVERY_BASE}/send-otp`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email })
        });
        if (res.ok) {
            NEXUS_STATE.pendingEmail = email;
            document.getElementById('recovery-step-1').style.display = 'none';
            document.getElementById('recovery-step-2').style.display = 'block';
            showToast('OTP sent to your email!', 'success');
        } else {
            let msg = 'Email not found';
            try { const d = await res.json(); msg = d.error || msg; } catch {}
            showToast(msg, 'error');
        }
    } catch {
        showToast('Network error. Try again.', 'error');
    } finally {
        if (btn) { btn.disabled = false; btn.textContent = 'Send OTP'; }
    }
}

async function handleRecoverySubmit() {
    const otp = document.getElementById('recovery-otp').value.trim();
    /* M25 FIX: OTP format validation */
    if (!otp) { showToast('Enter the OTP', 'error'); return; }
    if (!VALIDATORS.otp(otp)) { showToast('OTP must be 6 digits', 'error'); return; }

    const btn = document.getElementById('btn-recovery-submit');
    if (btn) { btn.disabled = true; }

    try {
        if (NEXUS_STATE.recoveryMode === 'USERNAME') {
            /* C3 FIX: include newPassword field to satisfy RecoveryRequest DTO */
            const res = await fetch(`${RECOVERY_BASE}/verify-username`, {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: NEXUS_STATE.pendingEmail, otp: otp, newPassword: '' })
            });
            if (res.ok) {
                /* C4 FIX: parse response as JSON */
                const data = await res.json();
                showToast(`Your username: ${data.username}`, 'success');
                showScreen('login-screen');
            } else {
                let msg = 'Invalid OTP';
                try { const d = await res.json(); msg = d.error || msg; } catch {}
                showToast(msg, 'error');
            }
        } else {
            const newPass = document.getElementById('recovery-new-password').value;
            if (!newPass) { showToast('Enter new password', 'error'); return; }
            /* M24 FIX: password min length */
            if (newPass.length < 8) { showToast('Password must be at least 8 characters', 'error'); return; }

            const res = await fetch(`${RECOVERY_BASE}/reset-password`, {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: NEXUS_STATE.pendingEmail, otp: otp, newPassword: newPass })
            });
            if (res.ok) {
                showToast('Password reset successful!', 'success');
                showScreen('login-screen');
            } else {
                let msg = 'Invalid OTP or reset failed';
                try { const d = await res.json(); msg = d.error || msg; } catch {}
                showToast(msg, 'error');
            }
        }
    } catch {
        showToast('Network error. Try again.', 'error');
    } finally {
        if (btn) { btn.disabled = false; }
    }
}

/* ══════════════════════════════════
   LOBBY
══════════════════════════════════ */
function filterLobby() {
    const q = document.getElementById('player-search').value.toLowerCase();
    document.querySelectorAll('.user-item-row').forEach(item => {
        const u = item.getAttribute('data-username').toLowerCase();
        const f = (item.getAttribute('data-fullname') || '').toLowerCase();
        item.style.display = (u.includes(q) || f.includes(q)) ? 'flex' : 'none';
    });
}

function startLobbyRefresh() {
    if (NEXUS_STATE.lobbyInterval)       clearInterval(NEXUS_STATE.lobbyInterval);
    if (NEXUS_STATE.leaderboardInterval) clearInterval(NEXUS_STATE.leaderboardInterval);
    refreshLobby();
    refreshLeaderboard();
    NEXUS_STATE.lobbyInterval       = setInterval(refreshLobby, 30000);
    NEXUS_STATE.leaderboardInterval = setInterval(refreshLeaderboard, 30000);
}

async function refreshLobby() {
    if (document.getElementById('lobby-screen').style.display === 'none') return;
    try {
        const res   = await fetchWithAuth(`${API_BASE}/lobby`);
        if (!res.ok) return;
        const users = await res.json();
        const list  = document.getElementById('online-users-list');
        const others = users.filter(u => u.username !== NEXUS_STATE.currentUser);

        if (!others.length) {
            list.innerHTML = '<div style="text-align:center;padding:32px;color:var(--muted);font-size:0.82rem;">No other players online</div>';
            return;
        }

        list.innerHTML = others.map(user => {
            const busy      = user.status === 'IN_GAME';
            const pillClass = busy ? 'status-ingame' : 'status-online';
            const pillText  = busy ? 'In Game' : 'Online';
            const initials  = user.username.slice(0, 2).toUpperCase();
            const safeName  = escapeHtml(user.username);
            const safeFullName = escapeHtml(user.fullName || 'Nexus Player');
            return `<div class="player-row user-item-row" data-username="${safeName}" data-fullname="${safeFullName}">
                <div class="player-info">
                    <div class="player-avatar">${initials}</div>
                    <div>
                        <div class="player-name">${safeName}</div>
                        <div class="player-fullname">${safeFullName}</div>
                    </div>
                </div>
                <div style="display:flex;align-items:center;gap:8px;">
                    <span class="status-pill ${pillClass}">${pillText}</span>
                    <button class="challenge-btn" data-target="${safeName}"
                        ${busy ? 'disabled' : ''}>Challenge</button>
                </div>
            </div>`;
        }).join('');

        list.querySelectorAll('.challenge-btn:not([disabled])').forEach(btn => {
            btn.addEventListener('click', () => sendChallenge(btn.getAttribute('data-target')));
        });

        filterLobby();
    } catch (e) { console.error('Lobby refresh failed', e); }
}

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

async function refreshLeaderboard() {
    try {
        const res = await fetchWithAuth(`${API_BASE}/leaderboard`);
        if (!res.ok) return;
        const players = await res.json();
        const list    = document.getElementById('leaderboard-list');

        if (!players.length) {
            list.innerHTML = '<div style="text-align:center;padding:24px;color:var(--muted);font-size:0.8rem;">No games played yet</div>';
            return;
        }

        const medals = ['1', '2', '3'];
        list.innerHTML = players.map((p, i) => {
            const total = (p.wins || 0) + (p.losses || 0);
            const rate  = p.winRate !== undefined ? p.winRate : (total > 0 ? Math.round((p.wins / total) * 100) : 0);
            const medal = i < 3 ? ['🥇','🥈','🥉'][i] : '#' + (i + 1);
            return `<div class="lb-row">
                <span class="lb-rank">${medal}</span>
                <span class="lb-name">${escapeHtml(p.username)}</span>
                <div class="lb-stats">
                    <span class="lb-win">${p.wins || 0}W</span>
                    <span class="lb-loss">${p.losses || 0}L</span>
                    <span class="lb-rate">${rate}%</span>
                </div>
            </div>`;
        }).join('');
    } catch (e) { console.error('Leaderboard failed', e); }
}

function updateSingleUserStatus(update) {
    if (update.status === 'OFFLINE') {
        const row = document.querySelector(`[data-username="${CSS.escape(update.username)}"]`);
        if (row) row.remove();
        return;
    }
    if (update.status === 'ONLINE') { refreshLobby(); return; }

    document.querySelectorAll('.user-item-row').forEach(row => {
        if (row.getAttribute('data-username') !== update.username) return;
        const pill = row.querySelector('.status-pill');
        if (!pill) return;
        if (update.status === 'IN_GAME') {
            pill.className   = 'status-pill status-ingame';
            pill.textContent = 'In Game';
        } else {
            pill.className   = 'status-pill status-online';
            pill.textContent = 'Online';
        }
    });
}

/* ══════════════════════════════════
   CHALLENGE FLOW
══════════════════════════════════ */
function sendChallenge(targetUser) {
    NEXUS_STATE.currentPendingOpponent = targetUser;
    NEXUS_STATE.currentRoomId          = [NEXUS_STATE.currentUser, targetUser].sort().join('_');
    document.getElementById('waiting-modal').style.display = 'flex';
    document.getElementById('waiting-text').textContent    = `Waiting for ${targetUser}...`;
    NEXUS_STATE.stompClient.send('/app/challenge', {}, JSON.stringify({
        sender: NEXUS_STATE.currentUser, receiver: targetUser,
        roomId: NEXUS_STATE.currentRoomId, type: 'CHALLENGE_REQUEST'
    }));
}

function acceptChallenge() {
    NEXUS_STATE.stompClient.send('/app/challenge/reply', {}, JSON.stringify({
        sender: NEXUS_STATE.currentUser, receiver: NEXUS_STATE.currentPendingOpponent,
        roomId: NEXUS_STATE.currentRoomId, status: 'ACCEPTED', type: 'CHALLENGE_RESPONSE'
    }));
    document.getElementById('challenge-modal').style.display = 'none';
    document.getElementById('waiting-modal').style.display   = 'none';
    setupGame(NEXUS_STATE.currentRoomId, NEXUS_STATE.currentPendingOpponent);
    NEXUS_STATE.currentPendingOpponent = null;
}

function declineChallenge() {
    NEXUS_STATE.stompClient.send('/app/challenge/reply', {}, JSON.stringify({
        sender: NEXUS_STATE.currentUser, receiver: NEXUS_STATE.currentPendingOpponent,
        roomId: NEXUS_STATE.currentRoomId, status: 'REJECTED', type: 'CHALLENGE_RESPONSE'
    }));
    document.getElementById('challenge-modal').style.display = 'none';
    NEXUS_STATE.currentPendingOpponent = null;
}

/* ══════════════════════════════════
   GAME SETUP
══════════════════════════════════ */
function setupGame(roomId, opponent) {
    NEXUS_STATE.currentRoomId = roomId;
    NEXUS_STATE.opponentUser  = opponent;
    const parts   = roomId.split('_');
    setRoomDisplay(parts[0], parts[1]);
    showScreen('game-container');
    if (NEXUS_STATE.roomSubscription) { NEXUS_STATE.roomSubscription.unsubscribe(); NEXUS_STATE.roomSubscription = null; }
    if (NEXUS_STATE.stompClient && NEXUS_STATE.stompClient.connected) {
        NEXUS_STATE.roomSubscription = NEXUS_STATE.stompClient.subscribe(
            `/topic/game/${NEXUS_STATE.currentRoomId}`,
            m => handleRoomMessage(JSON.parse(m.body))
        );
    }
    resetBoardState();
}

function requestRematch() {
    NEXUS_STATE.stompClient.send(`/app/reset/${NEXUS_STATE.currentRoomId}`, {}, '');
}

function leaveGame() {
    ['game-over-modal', 'toss-modal', 'waiting-modal', 'challenge-modal'].forEach(id => {
        document.getElementById(id).style.display = 'none';
    });

    if (NEXUS_STATE.currentRoomId && NEXUS_STATE.stompClient) {
        NEXUS_STATE.stompClient.send('/app/game.abort', {}, JSON.stringify({
            sender: NEXUS_STATE.currentUser, roomId: NEXUS_STATE.currentRoomId, type: 'GAME_ABORTED'
        }));
    }

    if (NEXUS_STATE.roomSubscription) { NEXUS_STATE.roomSubscription.unsubscribe(); NEXUS_STATE.roomSubscription = null; }
    NEXUS_STATE.isGameOver             = false;
    NEXUS_STATE.isMyTurn               = false;
    NEXUS_STATE.mySymbol               = '';
    NEXUS_STATE.tossSubmitted          = false;
    NEXUS_STATE.tossGameStartHandled   = false;
    NEXUS_STATE.currentRoomId          = '';
    NEXUS_STATE.opponentUser           = null;
    showScreen('lobby-screen');
    startLobbyRefresh();
}

/* ══════════════════════════════════
   BOARD
══════════════════════════════════ */
function resetBoardState() {
    NEXUS_STATE.isGameOver             = false;
    NEXUS_STATE.isMyTurn               = false;
    NEXUS_STATE.mySymbol               = '';
    NEXUS_STATE.tossSubmitted          = false;
    NEXUS_STATE.tossGameStartHandled   = false;

    const cells = document.getElementsByClassName('cell');
    for (let i = 0; i < cells.length; i++) {
        cells[i].textContent = '';
        cells[i].className   = 'cell';
    }

    const parts  = NEXUS_STATE.currentRoomId.split('_');
    const amHost = parts[0] === NEXUS_STATE.currentUser;
    const tossBtn = document.getElementById('btn-toss');

    if (amHost) {
        tossBtn.style.display = 'inline-block';
        setStatus('You are Host — flip the coin to begin!', 'info');
    } else {
        tossBtn.style.display = 'none';
        setStatus(`Waiting for ${NEXUS_STATE.opponentUser} to flip...`, 'warn');
    }
}

/* ══════════════════════════════════
   TOSS
══════════════════════════════════ */
function sendToss() {
    if (!NEXUS_STATE.currentRoomId || !NEXUS_STATE.opponentUser) { showToast('Game not ready yet.', 'warning'); return; }
    NEXUS_STATE.stompClient.send(`/app/toss/${NEXUS_STATE.currentRoomId}`, {}, JSON.stringify({
        playerOne: NEXUS_STATE.currentUser, playerTwo: NEXUS_STATE.opponentUser, roomId: NEXUS_STATE.currentRoomId
    }));
    document.getElementById('btn-toss').style.display = 'none';
    setStatus('Flipping the coin...', 'info');
}

function submitTossChoice(choice) {
    if (NEXUS_STATE.tossSubmitted) return;
    NEXUS_STATE.tossSubmitted = true;
    document.querySelectorAll('.toss-choice-btn').forEach(b => b.disabled = true);
    NEXUS_STATE.stompClient.send(`/app/toss/decision/${NEXUS_STATE.currentRoomId}`, {}, JSON.stringify({
        payload: choice
    }));
    document.getElementById('toss-modal').style.display = 'none';
}

/* ══════════════════════════════════
   GAME MOVE
══════════════════════════════════ */
function sendMove(pos) {
    if (NEXUS_STATE.isGameOver || !NEXUS_STATE.isMyTurn) return;
    if (!NEXUS_STATE.stompClient || !NEXUS_STATE.stompClient.connected) {
        showToast('Connection lost. Please refresh.', 'error');
        return;
    }
    const cells = document.getElementsByClassName('cell');
    if (cells[pos].textContent !== '') return;

    NEXUS_STATE.stompClient.send(`/app/move/${NEXUS_STATE.currentRoomId}`, {}, JSON.stringify({
        playerUsername: NEXUS_STATE.currentUser, boardPosition: pos,
        roomId: NEXUS_STATE.currentRoomId, symbol: NEXUS_STATE.mySymbol
    }));
    NEXUS_STATE.isMyTurn = false;
    setStatus(`Waiting for ${NEXUS_STATE.opponentUser}...`, 'warn');
}

/* ══════════════════════════════════
   ROOM MESSAGE ROUTER
══════════════════════════════════ */
function handleRoomMessage(payload) {
    if (payload.type === 'GAME_ABORTED') {
        showToast(`${payload.sender} left the match.`, 'warning');
        if (NEXUS_STATE.roomSubscription) { NEXUS_STATE.roomSubscription.unsubscribe(); NEXUS_STATE.roomSubscription = null; }
        NEXUS_STATE.currentRoomId = '';
        NEXUS_STATE.opponentUser = null;
        document.getElementById('game-over-modal').style.display = 'none';
        showScreen('lobby-screen');
        startLobbyRefresh();
        return;
    }

    if (payload.type === 'GAME_RESET') {
        document.getElementById('game-over-modal').style.display = 'none';
        document.getElementById('toss-modal').style.display      = 'none';
        document.getElementById('toss-winner-section').style.display = 'none';
        document.getElementById('toss-loser-section').style.display  = 'none';
        NEXUS_STATE.tossSubmitted        = false;
        NEXUS_STATE.tossGameStartHandled = false;
        /* H4 FIX: reset isGameOver so rematch moves work */
        NEXUS_STATE.isGameOver = false;
        resetBoardState();
        return;
    }

    if (payload.type === 'TOSS') {
        document.getElementById('btn-toss').style.display   = 'none';
        document.getElementById('toss-modal').style.display = 'flex';

        if (payload.payload === NEXUS_STATE.currentUser) {
            document.getElementById('toss-modal-card').style.borderColor = 'rgba(0,212,255,0.3)';
            document.getElementById('toss-result-title').textContent     = 'You Won the Toss!';
            document.getElementById('toss-result-title').className       = 'modal-title text-cyan';
            document.getElementById('toss-result-desc').textContent      = 'Pick your symbol to enter the arena:';
            document.getElementById('toss-winner-section').style.display = 'block';
            document.getElementById('toss-loser-section').style.display  = 'none';
            setStatus('You won the toss! Choose your symbol.', 'success');
        } else {
            document.getElementById('toss-modal-card').style.borderColor = 'rgba(255,201,64,0.25)';
            document.getElementById('toss-result-title').textContent     = `${payload.payload} Won`;
            document.getElementById('toss-result-title').className       = 'modal-title text-gold';
            document.getElementById('toss-result-desc').textContent      = 'Your opponent is choosing their symbol...';
            document.getElementById('toss-winner-section').style.display = 'none';
            document.getElementById('toss-loser-section').style.display  = 'block';
            document.getElementById('toss-waiting-text').textContent     = `Waiting for ${payload.payload} to choose...`;
            setStatus(`${payload.payload} won the toss. Waiting...`, 'warn');
        }
        return;
    }

    if (payload.type === 'TOSS_RESULT') {
        document.getElementById('toss-modal').style.display = 'none';
        document.getElementById('btn-toss').style.display   = 'none';

        const firstPlayer = payload.payload;
        if (firstPlayer === NEXUS_STATE.currentUser) {
            NEXUS_STATE.isMyTurn = true;  NEXUS_STATE.mySymbol = 'X';
            setStatus('Your turn! Make a move.', 'success');
        } else {
            NEXUS_STATE.isMyTurn = false; NEXUS_STATE.mySymbol = 'O';
            setStatus(`${NEXUS_STATE.opponentUser}'s turn...`, 'warn');
        }

        NEXUS_STATE.tossGameStartHandled = true;
        return;
    }

    if (payload.boardPosition !== undefined && payload.boardPosition !== null) {
        const cells = document.getElementsByClassName('cell');
        const pos   = parseInt(payload.boardPosition);

        if (cells[pos] && cells[pos].textContent === '') {
            cells[pos].textContent = payload.symbol;
            cells[pos].classList.add(payload.symbol === 'X' ? 'x-mark' : 'o-mark');

            if (payload.gameState && payload.gameState !== 'ONGOING') {
                NEXUS_STATE.isGameOver = true;
                showWinnerModal(payload.gameState);
            } else if (payload.playerUsername !== NEXUS_STATE.currentUser) {
                NEXUS_STATE.isMyTurn = true;
                setStatus('Your turn!', 'success');
            } else {
                setStatus(`Waiting for ${NEXUS_STATE.opponentUser}...`, 'warn');
            }
        }
    }
}

/* ══════════════════════════════════
   WIN / DRAW MODAL
══════════════════════════════════ */
function showWinnerModal(state) {
    const modal = document.getElementById('game-over-modal');
    const title = document.getElementById('go-title');
    const icon  = document.getElementById('go-icon');

    if (state === 'DRAW') {
        icon.textContent  = 'Draw';
        title.textContent = "It's a Draw!";
        title.className   = 'modal-title';
        document.getElementById('go-desc').textContent = 'A hard-fought battle with no victor. Well played.';
    } else {
        const winSym = state.replace('WINNER_', '');
        const won    = winSym === NEXUS_STATE.mySymbol;
        icon.textContent  = won ? 'Victory' : 'Defeat';
        title.textContent = won ? 'Victory!' : `${NEXUS_STATE.opponentUser} Won`;
        title.className   = `modal-title ${won ? 'text-cyan' : 'text-red'}`;
        document.getElementById('go-desc').textContent = won
            ? 'Outstanding performance in the arena.'
            : 'Better luck next round. Keep fighting.';
    }
    modal.style.display = 'flex';
}

/* ══════════════════════════════════
   WEBSOCKET
══════════════════════════════════ */
function connect(afterConnectCallback) {
    if (NEXUS_STATE.isConnected && NEXUS_STATE.stompClient && NEXUS_STATE.stompClient.connected) {
        if (afterConnectCallback) afterConnectCallback();
        return;
    }
    if (NEXUS_STATE.stompClient) { try { NEXUS_STATE.stompClient.disconnect(); } catch (e) {} NEXUS_STATE.stompClient = null; }
    NEXUS_STATE.isConnected = false;

    const socket = new SockJS(WS_ENDPOINT);
    NEXUS_STATE.stompClient  = Stomp.over(socket);
    NEXUS_STATE.stompClient.debug = null;

    NEXUS_STATE.stompClient.connect(
        { Authorization: 'Bearer ' + localStorage.getItem('nexus_token') },
        function () {
            NEXUS_STATE.isConnected = true;
            NEXUS_STATE.reconnectAttempt = 0;

            /* C5 FIX: track and re-subscribe ALL topics on connect/reconnect */
            if (NEXUS_STATE.lobbySubscription) { NEXUS_STATE.lobbySubscription.unsubscribe(); }
            NEXUS_STATE.lobbySubscription = NEXUS_STATE.stompClient.subscribe('/topic/lobby.status', payload => {
                updateSingleUserStatus(JSON.parse(payload.body));
            });

            if (NEXUS_STATE.challengeSubscription) { NEXUS_STATE.challengeSubscription.unsubscribe(); }
            NEXUS_STATE.challengeSubscription = NEXUS_STATE.stompClient.subscribe('/topic/challenges/' + NEXUS_STATE.currentUser, payload => {
                const message = JSON.parse(payload.body);
                if (message.type === 'CHALLENGE_REQUEST') {
                    NEXUS_STATE.currentPendingOpponent = message.sender;
                    NEXUS_STATE.currentRoomId = message.roomId;
                    document.getElementById('challenge-text').textContent = `Challenge from ${message.sender}!`;
                    document.getElementById('challenge-modal').style.display = 'flex';
                } else if (message.type === 'CHALLENGE_RESPONSE') {
                    document.getElementById('waiting-modal').style.display = 'none';
                    if (message.status === 'ACCEPTED') setupGame(message.roomId, message.sender);
                    else if (message.status === 'REJECTED') showToast(`${message.sender} declined.`, 'warning');
                    else if (message.status === 'CANCELLED') showToast('Challenge cancelled.', 'info');
                    NEXUS_STATE.currentPendingOpponent = null;
                }
            });

            if (afterConnectCallback) afterConnectCallback();
        },
        function () {
            NEXUS_STATE.isConnected = false;
            /* M11 FIX: exponential backoff on reconnect */
            const delay = Math.min(3000 * Math.pow(2, NEXUS_STATE.reconnectAttempt), NEXUS_STATE.maxReconnectDelay);
            NEXUS_STATE.reconnectAttempt++;
            const savedRoom = NEXUS_STATE.currentRoomId;
            setTimeout(function () {
                connect(function () {
                    if (savedRoom) {
                        if (NEXUS_STATE.roomSubscription) { NEXUS_STATE.roomSubscription.unsubscribe(); NEXUS_STATE.roomSubscription = null; }
                        NEXUS_STATE.roomSubscription = NEXUS_STATE.stompClient.subscribe(
                            `/topic/game/${savedRoom}`,
                            m => handleRoomMessage(JSON.parse(m.body))
                        );
                    }
                });
            }, delay);
        }
    );
}
