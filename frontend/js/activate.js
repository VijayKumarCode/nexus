function hideAllStates() {
    document.querySelectorAll(".state").forEach(el => {
        el.classList.remove("active");
    });
}

function showState(id) {
    hideAllStates();
    document.getElementById(id).classList.add("active");
}

function initializeActivationPage() {
    const params = new URLSearchParams(window.location.search);

    const success = params.get("success");

    if (success === "true") {
        showState("success-state");

        setTimeout(() => {
            window.location.href = "/";
        }, 5000);

        return;
    }

    if (success === "false") {
        showState("error-state");
        document.getElementById("error-message").textContent =
            "The activation link is invalid or has expired.";
        return;
    }

    showState("error-state");
    document.getElementById("error-message").textContent =
        "Invalid activation request.";
}

function redirectToLogin() {
    window.location.href = "/";
}

function requestNewLink() {
    window.location.href = "/resend-activation";
}

document.addEventListener(
    "DOMContentLoaded",
    initializeActivationPage
);