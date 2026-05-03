const stateBadge = document.querySelector("#stateBadge");
const output = document.querySelector("#output");
const enableBtn = document.querySelector("#enableBtn");
const disableBtn = document.querySelector("#disableBtn");
const refreshBtn = document.querySelector("#refreshBtn");
const onlineCheckBtn = document.querySelector("#onlineCheckBtn");

function setBusy(isBusy) {
  enableBtn.disabled = isBusy;
  disableBtn.disabled = isBusy;
  refreshBtn.disabled = isBusy;
  onlineCheckBtn.disabled = isBusy;
}

function writeTerminal(command, text) {
  output.textContent = `$ ${command}\n${text}`;
}

function renderStatus(data) {
  const state = data.state || "unknown";
  stateBadge.textContent =
    state === "enabled" ? "Enabled" : state === "disabled" ? "Disabled" : "Unknown";
  stateBadge.className = `badge ${state}`;

  const rows = data.interfaces || [];
  writeTerminal(
    "ipv6_manager status",
    `IPv6: ${state}\nKernel entries: ${rows.length}`
  );
}

async function requestJson(path) {
  const response = await fetch(path, { cache: "no-store" });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  return response.json();
}

async function refreshStatus() {
  setBusy(true);
  try {
    const data = await requestJson("/api/status");
    if (!data.ok) {
      throw new Error(data.error || "status failed");
    }
    renderStatus(data);
  } catch (error) {
    stateBadge.textContent = "Error";
    stateBadge.className = "badge error";
    output.textContent = String(error.message || error);
  } finally {
    setBusy(false);
  }
}

async function runAction(action) {
  setBusy(true);
  writeTerminal(`ipv6_manager ${action}`, "Running...");
  try {
    const result = await requestJson(`/api/${action}`);
    writeTerminal(`ipv6_manager ${action}`, result.output || JSON.stringify(result));
    await refreshStatus();
  } catch (error) {
    stateBadge.textContent = "Error";
    stateBadge.className = "badge error";
    writeTerminal(`ipv6_manager ${action}`, String(error.message || error));
  } finally {
    setBusy(false);
  }
}

async function runOnlineCheck() {
  setBusy(true);
  writeTerminal("online_ipv6_check", "Running...");
  try {
    const result = await requestJson("/api/online-check");
    writeTerminal("online_ipv6_check", result.output || JSON.stringify(result));
  } catch (error) {
    writeTerminal("online_ipv6_check", String(error.message || error));
  } finally {
    setBusy(false);
  }
}

enableBtn.addEventListener("click", () => runAction("enable"));
disableBtn.addEventListener("click", () => runAction("disable"));
refreshBtn.addEventListener("click", refreshStatus);
onlineCheckBtn.addEventListener("click", runOnlineCheck);
