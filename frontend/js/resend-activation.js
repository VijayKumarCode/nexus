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
    const stateEl = getElement(id);
    if (stateEl) {
        stateEl.classList.add("active");
    }
}

function validateEmail(email) {
    const re = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    return re.test(email);
}

function showFieldError(message) {
    const errorEl = getElement("email-error");
    const emailEl = getElement("email");
    if (errorEl) errorEl.textContent = message;
    if (emailEl) {
        emailEl.classList.add("invalid");
        emailEl.setAttribute("aria-invalid", "true");
    }
}

function clearFieldError() {
    const errorEl = getElement("email-error");
    const emailEl = getElement("email");
    if (errorEl) errorEl.textContent = "";
    if (emailEl) {
        emailEl.classList.remove("invalid");
        emailEl.setAttribute("aria-invalid", "false");
    }
}

function setLoading(loading) {
    const submitBtn = getElement("submit-btn");
    if (!submitBtn) return; // Safeguard if the button element is completely missing

    const btnText = submitBtn.querySelector(".btn-text");
    const btnSpinner = submitBtn.querySelector(".btn-spinner");

    submitBtn.disabled = loading;

    // Safely adjust display layout configurations if the elements exist
    if (btnText) {
        btnText.style.display = loading ? "none" : "inline";
    }
    if (btnSpinner) {
        btnSpinner.style.display = loading ? "inline-block" : "none";
    }

    // Safe fallback: If helper spans are completely missing, adjust the raw button text directly
    if (!btnText && !btnSpinner) {
        if (loading) {
            submitBtn.dataset.originalText = submitBtn.textContent;
            submitBtn.textContent = "Sending...";
        } else if (submitBtn.dataset.originalText) {
            submitBtn.textContent = submitBtn.dataset.originalText;
        }
    }
}

function startCooldown() {
    let remaining = COOLDOWN_SECONDS;
    const resendBtn = getElement("resend-btn");
    const countdown = getElement("countdown");

    if (resendBtn) resendBtn.disabled = true;
    if (countdown) countdown.textContent = remaining;

    cooldownTimer = setInterval(() => {
        remaining--;
        if (countdown) countdown.textContent = remaining;

        if (remaining <= 0) {
            clearInterval(cooldownTimer);
            if (resendBtn) {
                resendBtn.disabled = false;
                resendBtn.innerHTML = "Resend Link";
            }
        }
    }, 1000);
}

async function handleSubmit(e) {
    e.preventDefault();
    clearFieldError();

    const emailEl = getElement("email");
    if (!emailEl) return;
    const email = emailEl.value.trim();

    if (!email) {
        showFieldError("Email address is required");
        emailEl.focus();
        return;
    }

    if (!validateEmail(email)) {
        showFieldError("Please enter a valid email address");
        emailEl.focus();
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

        // Defend against server responses that are not JSON format (like 502 Bad Gateway HTML pages)
        let data = {};
        const contentType = response.headers.get("content-type");
        if (contentType && contentType.includes("application/json")) {
            data = await response.json();
        }

        if (response.ok) {
            showState("success-state");
            startCooldown();
        } else {
            showState("error-state");
            const errorMessageEl = getElement("error-message");
            if (errorMessageEl) {
                errorMessageEl.textContent = data.error || data.message || "Unable to resend activation email.";
            }
        }

    } catch (err) {
        console.error("Network or parsing error details:", err);
        showState("error-state");
        const errorMessageEl = getElement("error-message");
        if (errorMessageEl) {
            errorMessageEl.textContent = "Unable to connect to server.";
        }
    } finally {
        setLoading(false);
    }
}

function resetForm() {
    clearFieldError();
    const emailEl = getElement("email");
    if (emailEl) {
        emailEl.value = "";
        showState("form-state");
        emailEl.focus();
    } else {
        showState("form-state");
    }
}

// Attach event handlers safely checking if nodes exist
const resendForm = getElement("resend-form");
if (resendForm) {
    resendForm.addEventListener("submit", handleSubmit);
}

const emailInput = getElement("email");
if (emailInput) {
    emailInput.addEventListener("input", () => {
        if (emailInput.classList.contains("invalid")) {
            clearFieldError();
        }
    });

    emailInput.addEventListener("blur", () => {
        const email = emailInput.value.trim();
        if (email && !validateEmail(email)) {
            showFieldError("Please enter a valid email address");
        }
    });
}

document.addEventListener("DOMContentLoaded", () => {
    showState("form-state");
    const emailEl = getElement("email");
    if (emailEl) emailEl.focus();
});

window.addEventListener("beforeunload", () => {
    if (cooldownTimer) {
        clearInterval(cooldownTimer);
    }
});