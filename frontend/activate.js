const API_BASE_URL = window.location.hostname === 'localhost'
    ? 'http://localhost:8080'
    : 'https://nexus-yxa3.onrender.com';

function getQueryParam(param) {
    const urlParams = new URLSearchParams(window.location.search);
    return urlParams.get(param);
}

function showState(stateId) {
    document.querySelectorAll('.state').forEach(el => el.classList.remove('active'));
    document.getElementById(stateId).classList.add('active');
}

async function activateAccount() {
    const token = getQueryParam('token');

    if (!token) {
        showState('error-state');
        document.getElementById('error-message').textContent =
            'No activation token found in URL.';
        return;
    }

    // Basic token format validation
    if (!/^[a-fA-F0-9]{64}$/.test(token)) {
        showState('error-state');
        document.getElementById('error-message').textContent =
            'Invalid token format.';
        return;
    }

    showState('loading-state');

    try {
        const response = await fetch(`${API_BASE_URL}/api/auth/activate`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'application/json'
            },
            body: JSON.stringify({ token: token })
        });

        const data = await response.json();

        if (response.ok && data.success) {
            showState('success-state');
            // Auto-redirect after 5 seconds
            setTimeout(redirectToLogin, 5000);
        } else {
            if (data.error === 'TOKEN_EXPIRED') {
                showState('expired-state');
            } else {
                showState('error-state');
                document.getElementById('error-message').textContent =
                    data.message || 'Activation failed. Please try again.';
            }
        }
    } catch (error) {
        console.error('Activation error:', error);
        showState('error-state');
        document.getElementById('error-message').textContent =
            'Network error. Please check your connection and try again.';
    }
}

function redirectToLogin() {
    window.location.href = '/login';
}

function requestNewLink() {
    window.location.href = '/resend-activation';
}

// Initialize on page load
document.addEventListener('DOMContentLoaded', activateAccount);