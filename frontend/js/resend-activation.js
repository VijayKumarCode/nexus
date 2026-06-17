const API_BASE_URL = CONFIG.API_BASE_URL;
const COOLDOWN_SECONDS = CONFIG.COOLDOWN_SECONDS;
let cooldownTimer = null;

function getElement(id) {
    return document.getElementById(id);
}

function hideAllStates() {
    document.querySelectorAll(".state").forEach(el => {
        el.classList.remove("active");
    });
}

function showState(id) {
    hideAllStates();
    document.getElementById(id).classList.add("active");
}

function validateEmail(email) {
    const re = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    return re.test(email);
}

function showFieldError(message) {
    getElement("email-error").textContent = message;
    getElement("email").classList.add("invalid");
    getElement("email").setAttribute("aria-invalid", "true");
}

function clearFieldError() {
    getElement("email-error").textContent = "";
    getElement("email").classList.remove("invalid");
    getElement("email").setAttribute("aria-invalid", "false");
}

function setLoading(loading) {
    const submitBtn = getElement("submit-btn");
    const btnText = submitBtn.querySelector(".btn-text");
    const btnSpinner = submitBtn.querySelector(".btn-spinner");

    submitBtn.disabled = loading;
    btnText.style.display = loading ? "none" : "inline";
    btnSpinner.style.display = loading ? "inline-block" : "none";
}

function startCooldown() {
    let remaining = COOLDOWN_SECONDS;
    const resendBtn = getElement("resend-btn");
    const countdown = getElement("countdown");

    resendBtn.disabled = true;
    countdown.textContent = remaining;

    cooldownTimer = setInterval(() => {
        remaining--;
        countdown.textContent = remaining;

        if (remaining <= 0) {
            clearInterval(cooldownTimer);
            resendBtn.disabled = false;
            resendBtn.innerHTML = "Resend Link";
        }
    }, 1000);
}

async function handleSubmit(e) {
    e.preventDefault();
    clearFieldError();

    const email = getElement("email").value.trim();

    if (!email) {
        showFieldError("Email address is required");
        getElement("email").focus();
        return;
    }

    if (!validateEmail(email)) {
        showFieldError("Please enter a valid email address");
        getElement("email").focus();
        return;
    }

    setLoading(true);
    showState("loading-state");

    try {
        const response = await fetch(
            `${API_BASE_URL}${CONFIG.ENDPOINTS.RESEND_ACTIVATION}`,
            {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "Accept": "application/json"
                },
                body: JSON.stringify({ email: email })
            }
        );

        const data = await response.json();

        if (response.ok) {
            showState("success-state");
            startCooldown();
        } else {
            showState("error-state");
            getElement("error-message").textContent =
                data.error || "Unable to resend activation email.";
        }

    } catch (err) {
        console.error(err);
        showState("error-state");
        getElement("error-message").textContent =
            "Unable to connect to server.";
    } finally {
        setLoading(false);
    }
}

function resetForm() {
    clearFieldError();
    getElement("email").value = "";
    showState("form-state");
    getElement("email").focus();
}

getElement("resend-form").addEventListener("submit", handleSubmit);

getElement("email").addEventListener("input", () => {
    if (getElement("email").classList.contains("invalid")) {
        clearFieldError();
    }
});

getElement("email").addEventListener("blur", () => {
    const email = getElement("email").value.trim();
    if (email && !validateEmail(email)) {
        showFieldError("Please enter a valid email address");
    }
});

document.addEventListener("DOMContentLoaded", () => {
    showState("form-state");
    getElement("email").focus();
});

window.addEventListener("beforeunload", () => {
    if (cooldownTimer) {
        clearInterval(cooldownTimer);
    }
});