const API_BASE_URL = CONFIG.API_BASE_URL;

function getToken() {
    return new URLSearchParams(window.location.search).get("token");
}

function hideAllStates() {
    document.querySelectorAll(".state").forEach(el => {
        el.style.display = "none";
    });
}

function showState(id) {
    hideAllStates();
    document.getElementById(id).style.display = "block";
}

async function activateAccount() {
    const token = getToken();

    if (!token) {
        showState("error-state");
        document.getElementById("error-message").textContent =
            "Missing activation token.";
        return;
    }

    showState("loading-state");

    try {
        const response = await fetch(
            `${API_BASE_URL}${CONFIG.ENDPOINTS.ACTIVATE}?token=${encodeURIComponent(token)}`
        );

        const data = await response.json();

        if (response.ok) {
            showState("success-state");
            setTimeout(() => {
                window.location.href = "/";
            }, 5000);
        } else {
            showState("error-state");
            document.getElementById("error-message").textContent =
                data.error || "Activation failed.";
        }
    } catch (err) {
        console.error(err);
        showState("error-state");
        document.getElementById("error-message").textContent =
            "Unable to connect to server.";
    }
}

function redirectToLogin() {
    window.location.href = "/";
}

function requestNewLink() {
    window.location.href = "/resend-activation";
}

document.addEventListener("DOMContentLoaded", activateAccount);